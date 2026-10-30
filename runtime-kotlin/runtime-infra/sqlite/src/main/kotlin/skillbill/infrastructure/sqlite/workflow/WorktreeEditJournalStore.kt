package skillbill.infrastructure.sqlite.workflow

import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.parseWorktreeEditJournalInstant
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.sql.Connection
import java.time.Instant

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
    deleteInstants(workflowId) { existing -> existing == tick.recordedAt }
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
    val latestInstant = latestInstant(workflowId) ?: return null
    return connection.prepareStatement(
      """
      SELECT phase_id, recorded_at, path, lines_added, lines_removed, source
      FROM worktree_edit_journal
      WHERE workflow_id = ?
      ORDER BY id
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { resultSet ->
        var tick: WorktreeEditTick? = null
        val entries = mutableListOf<GoalObservabilityFileDiffStat>()
        while (resultSet.next()) {
          val recordedAt = parseWorktreeEditJournalInstant(resultSet.getString("recorded_at"))
          if (recordedAt != latestInstant) continue
          val source = WorktreeEditSource.fromWire(resultSet.getString("source"))
          if (source != null) {
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

  override fun deleteTicksBefore(
    workflowId: String,
    recordedAt: Instant,
  ): Int {
    if (workflowId.isBlank()) return 0
    return deleteInstants(workflowId) { existing -> existing.isBefore(recordedAt) }
  }

  private fun latestInstant(workflowId: String): Instant? =
    connection.prepareStatement(
      "SELECT DISTINCT recorded_at FROM worktree_edit_journal WHERE workflow_id = ?",
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { resultSet ->
        var latest: Instant? = null
        while (resultSet.next()) {
          val candidate = parseWorktreeEditJournalInstant(resultSet.getString("recorded_at"))
          if (latest == null || candidate.isAfter(latest)) latest = candidate
        }
        latest
      }
    }

  private fun deleteInstants(
    workflowId: String,
    shouldDelete: (Instant) -> Boolean,
  ): Int {
    val timestamps =
      connection.prepareStatement(
        "SELECT DISTINCT recorded_at FROM worktree_edit_journal WHERE workflow_id = ?",
      ).use { statement ->
        statement.bindAll(workflowId)
        statement.executeQuery().use { resultSet ->
          buildList {
            while (resultSet.next()) {
              val raw = resultSet.getString("recorded_at")
              if (shouldDelete(parseWorktreeEditJournalInstant(raw))) add(raw)
            }
          }
        }
      }
    return timestamps.sumOf { timestamp ->
      connection.prepareStatement(
        "DELETE FROM worktree_edit_journal WHERE workflow_id = ? AND recorded_at = ?",
      ).use { statement ->
        statement.bindAll(workflowId, timestamp)
        statement.executeUpdate()
      }
    }
  }
}
