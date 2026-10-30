package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.parseWorktreeEditJournalInstant
import java.sql.Connection
import java.time.Instant

internal fun pruneWorktreeEditJournalToLatestTick(connection: Connection) {
  if (!DatabaseColumnMigrations.tableExists(connection, "worktree_edit_journal")) return
  val recordedInstants = mutableMapOf<String, MutableList<Pair<String, Instant>>>()
  connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT DISTINCT workflow_id, recorded_at FROM worktree_edit_journal",
    ).use { rows ->
      while (rows.next()) {
        val workflowId = rows.getString("workflow_id")
        val raw = rows.getString("recorded_at")
        recordedInstants.getOrPut(workflowId) { mutableListOf() } +=
          raw to parseWorktreeEditJournalInstant(raw)
      }
    }
  }
  recordedInstants.forEach { (workflowId, values) ->
    val latest = values.maxOf { (_, instant) -> instant }
    val olderValues = values.filter { (_, instant) -> instant.isBefore(latest) }.map { (raw, _) -> raw }
    olderValues.forEach { raw ->
      connection.prepareStatement(
        "DELETE FROM worktree_edit_journal WHERE workflow_id = ? AND recorded_at = ?",
      ).use { statement ->
        statement.bindAll(workflowId, raw)
        statement.executeUpdate()
      }
    }
  }
}
