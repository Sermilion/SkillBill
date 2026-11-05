package skillbill.infrastructure.sqlite.core.migration.area.operation

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection

internal fun addOperationProposalCards(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS operation_launch_contexts (
        launch_id TEXT PRIMARY KEY,
        operation_id TEXT NOT NULL,
        repository_identity TEXT NOT NULL,
        database_identity TEXT NOT NULL,
        checkout_root TEXT NOT NULL,
        branch TEXT NOT NULL,
        original_arguments_json TEXT NOT NULL,
        selected_agent TEXT NOT NULL,
        selected_profile TEXT,
        holder_id TEXT NOT NULL
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS operation_proposal_cards (
        token TEXT PRIMARY KEY,
        launch_id TEXT NOT NULL,
        operation_id TEXT NOT NULL,
        repository_identity TEXT NOT NULL,
        status TEXT NOT NULL CHECK (status IN (
          'awaiting_confirmation', 'confirming', 'completed', 'refused', 'failed', 'cancelled', 'superseded',
          'consumed_uncertain'
        )),
        record_json TEXT NOT NULL
      )
      """.trimIndent(),
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_operation_proposal_cards_launch ON operation_proposal_cards(launch_id)",
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_operation_proposal_cards_awaiting ON operation_proposal_cards(" +
        "repository_identity, operation_id) WHERE status = 'awaiting_confirmation'",
    )
  }
}

internal fun addOperationLaunchRetention(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(connection, "operation_launch_contexts", "retention", "TEXT")
}

internal fun addOperationConfirmationExecutions(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS operation_confirmation_executions (
        execution_id INTEGER PRIMARY KEY AUTOINCREMENT,
        token TEXT NOT NULL,
        launch_id TEXT NOT NULL,
        state TEXT NOT NULL CHECK (state IN ('admitted', 'claimed', 'retired')),
        admission_host TEXT NOT NULL,
        admission_boot TEXT NOT NULL,
        admission_pid INTEGER NOT NULL,
        admission_birth TEXT NOT NULL,
        invocation_id TEXT,
        child_host TEXT,
        child_boot TEXT,
        child_pid INTEGER,
        child_birth TEXT,
        cleanup_pending INTEGER NOT NULL DEFAULT 0,
        result_evidence TEXT
      )
      """.trimIndent(),
    )
    statement.execute(
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_operation_confirmation_executions_active " +
        "ON operation_confirmation_executions(token) WHERE state != 'retired'",
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_operation_confirmation_executions_cleanup " +
        "ON operation_confirmation_executions(token) WHERE cleanup_pending = 1",
    )
  }
}
