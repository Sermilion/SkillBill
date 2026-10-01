package skillbill.infrastructure.sqlite.workflow

import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.sql.Connection
import java.time.Instant
import java.time.format.DateTimeParseException

internal class WorktreeEditJournalStore(
  private val connection: Connection,
) : WorktreeEditJournalRepository {
  override fun append(
    workflowId: String,
    tick: WorktreeEditTick,
  ) {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    if (tick.entries.isEmpty()) return
    val latest = latestTick(workflowId)
    if (latest != null && tick.recordedAt.isBefore(latest.recordedAt)) return
    if (latest != null && tick.recordedAt == latest.recordedAt) {
      connection.prepareStatement(
        "DELETE FROM worktree_edit_journal WHERE workflow_id = ? AND recorded_at = ?",
      ).use { statement ->
        statement.bindAll(workflowId, tick.recordedAt.toString())
        statement.executeUpdate()
      }
    }
    connection.prepareStatement(
      """
      INSERT INTO worktree_edit_journal (
        workflow_id, phase_id, recorded_at, path, lines_added, lines_removed, source
      )
      VALUES (?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      tick.entries.forEach { entry ->
        statement.bindAll(
          workflowId,
          tick.phaseId,
          tick.recordedAt.toString(),
          entry.path,
          entry.insertions,
          entry.deletions,
          tick.source.wireValue,
        )
        statement.executeUpdate()
      }
    }
  }

  override fun latestTick(workflowId: String): WorktreeEditTick? {
    if (workflowId.isBlank()) return null
    return connection.prepareStatement(
      """
      SELECT phase_id, recorded_at, path, lines_added, lines_removed, source
      FROM worktree_edit_journal
      WHERE workflow_id = ?
        AND recorded_at = (SELECT MAX(recorded_at) FROM worktree_edit_journal WHERE workflow_id = ?)
      ORDER BY id
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId, workflowId)
      statement.executeQuery().use { resultSet ->
        var tick: WorktreeEditTick? = null
        val entries = mutableListOf<GoalObservabilityFileDiffStat>()
        while (resultSet.next()) {
          val recordedAt = parseInstant(resultSet.getString("recorded_at"))
          val source = recordedAt?.let { WorktreeEditSource.fromWire(resultSet.getString("source")) }
          if (recordedAt != null && source != null) {
            if (tick == null) {
              tick =
                WorktreeEditTick(
                  recordedAt = recordedAt,
                  phaseId = resultSet.getString("phase_id"),
                  source = source,
                  entries = entries,
                )
            }
            entries +=
              GoalObservabilityFileDiffStat(
                path = resultSet.getString("path"),
                insertions = resultSet.getInt("lines_added"),
                deletions = resultSet.getInt("lines_removed"),
              )
          }
        }
        tick?.copy(entries = entries.toList())
      }
    }
  }

  override fun trimToCap(
    workflowId: String,
    maxRows: Int,
  ): Int {
    if (workflowId.isBlank() || maxRows < 0) return 0
    var deleted = 0
    while (true) {
      val oldest = nextTrimTarget(workflowId, maxRows) ?: break
      deleted +=
        connection.prepareStatement(
          "DELETE FROM worktree_edit_journal WHERE workflow_id = ? AND recorded_at = ?",
        ).use { statement ->
          statement.bindAll(workflowId, oldest)
          statement.executeUpdate()
        }
    }
    return deleted
  }

  private fun nextTrimTarget(
    workflowId: String,
    maxRows: Int,
  ): String? {
    if (rowCount(workflowId) <= maxRows) return null
    val oldest = oldestRecordedAt(workflowId) ?: return null
    val newest = newestRecordedAt(workflowId) ?: return null
    return oldest.takeUnless { it == newest }
  }

  private fun rowCount(workflowId: String): Int =
    connection.prepareStatement(
      "SELECT COUNT(*) FROM worktree_edit_journal WHERE workflow_id = ?",
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getInt(1) else 0 }
    }

  private fun oldestRecordedAt(workflowId: String): String? =
    connection.prepareStatement(
      "SELECT MIN(recorded_at) FROM worktree_edit_journal WHERE workflow_id = ?",
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString(1) else null }
    }

  private fun newestRecordedAt(workflowId: String): String? =
    connection.prepareStatement(
      "SELECT MAX(recorded_at) FROM worktree_edit_journal WHERE workflow_id = ?",
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString(1) else null }
    }

  private fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
      Instant.parse(raw)
    } catch (_: DateTimeParseException) {
      null
    }
  }
}
