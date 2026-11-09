package skillbill.infrastructure.sqlite.workflow.goalrunner

import skillbill.contracts.workflow.goal.GoalArchiveEnvelopeKeys
import skillbill.error.goal.invalidGoalArchiveSchemaError
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.goalrunner.model.GoalArchiveTableRow
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.util.Base64

private val RESTORE_SKIP_TABLES =
  setOf(
    "feature_task_runtime_worker_leases",
    "feature_task_execution_identities",
  )

private val RESTORE_ALLOWED_TABLES =
  RESTORE_SKIP_TABLES +
    setOf(
      "worktree_edit_journal",
      "monitor_ci_cycles",
      "producer_output_evidence",
      "rejected_output_diagnostics",
      "agent_activity_stamps",
      "goal_subtask_events",
      "feature_task_phase_settlements",
      "standalone_phase_status",
      "goal_issue_progress",
      "goal_run_sessions",
      "feature_task_runtime_sessions",
      "goal_runner_controls",
    )

private val SQL_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

internal fun restoreOwnedGoalTables(
  connection: Connection,
  ownedTables: GoalArchiveOwnedTables,
) {
  ownedTables.orderedTables().forEach { (table, rows) ->
    if (table !in RESTORE_ALLOWED_TABLES) {
      throw invalidGoalArchiveSchemaError(
        GoalArchiveEnvelopeKeys.OWNED_TABLES_JSON,
        table,
        "is not an owned archive table",
      )
    }
    if (table in RESTORE_SKIP_TABLES || rows.isEmpty()) return@forEach
    val blobColumns = blobColumnNames(connection, table)
    val rowsToRestore =
      if (table == "worktree_edit_journal") {
        rows.sortedBy(::archivedJournalId)
      } else {
        rows
      }
    rowsToRestore.forEach { row ->
      insertArchiveRow(connection, table, row, blobColumns, omitJournalId = table == "worktree_edit_journal")
    }
  }
}

private fun insertArchiveRow(
  connection: Connection,
  table: String,
  row: GoalArchiveTableRow,
  blobColumns: Set<String>,
  omitJournalId: Boolean,
) {
  val entries = row.orderedEntries().filterNot { (name, _) -> omitJournalId && name == "id" }
  if (entries.isEmpty()) return
  val columns = entries.joinToString(", ") { (name, _) -> quotedIdentifier(name) }
  val values = entries.joinToString(", ") { "?" }
  connection.prepareStatement(
    "INSERT INTO ${quotedIdentifier(table)} ($columns) VALUES ($values)",
  ).use { statement ->
    entries.forEachIndexed { index, (name, value) ->
      bindArchiveRestoreValue(statement, index + 1, value, name in blobColumns)
    }
    statement.executeUpdate()
  }
}

private fun archivedJournalId(row: GoalArchiveTableRow): Long =
  (row.orderedEntries().firstOrNull { (name, _) -> name == "id" }?.second as? Number)?.toLong()
    ?: throw invalidGoalArchiveSchemaError(
      GoalArchiveEnvelopeKeys.OWNED_TABLES_JSON,
      "worktree_edit_journal.id",
      "is missing or is not numeric",
    )

private fun bindArchiveRestoreValue(
  statement: PreparedStatement,
  index: Int,
  value: Any?,
  blobColumn: Boolean,
) {
  when {
    value == null -> statement.setNull(index, if (blobColumn) Types.BLOB else Types.NULL)
    blobColumn && value is ByteArray -> statement.setBytes(index, value)
    blobColumn && value is String -> statement.setBytes(index, decodeRequiredBase64(value))
    value is String -> statement.setString(index, value)
    value is Number -> statement.setObject(index, value)
    value is Boolean -> statement.setBoolean(index, value)
    else -> statement.setString(index, value.toString())
  }
}

private fun decodeRequiredBase64(value: String): ByteArray =
  try {
    Base64.getDecoder().decode(value)
  } catch (error: IllegalArgumentException) {
    throw invalidGoalArchiveSchemaError(
      GoalArchiveEnvelopeKeys.OWNED_TABLES_JSON,
      "payload",
      "binary column is not valid base64",
      error,
    )
  }

private fun blobColumnNames(
  connection: Connection,
  table: String,
): Set<String> =
  connection.prepareStatement("SELECT name, type FROM pragma_table_info(?)").use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { rows ->
      buildSet {
        while (rows.next()) {
          val declaredType = rows.getString(2).orEmpty().uppercase()
          if ("BLOB" in declaredType || "BINARY" in declaredType) {
            add(rows.getString(1))
          }
        }
      }
    }
  }

private fun quotedIdentifier(name: String): String {
  if (!SQL_IDENTIFIER.matches(name)) {
    throw invalidGoalArchiveSchemaError(
      GoalArchiveEnvelopeKeys.OWNED_TABLES_JSON,
      name,
      "is not a SQL identifier",
    )
  }
  return "\"$name\""
}
