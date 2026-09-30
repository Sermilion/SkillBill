package skillbill.infrastructure.sqlite.core.migration.area

import java.sql.Connection

internal object OperationProposalsMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE IF NOT EXISTS operation_proposals (
          token TEXT PRIMARY KEY,
          operation_id TEXT NOT NULL,
          repo_root TEXT NOT NULL,
          anchors_json TEXT NOT NULL,
          proposal_value TEXT NOT NULL,
          created_at TEXT NOT NULL,
          superseded_at TEXT,
          consumed_at TEXT
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_operation_proposals_operation_repo
        ON operation_proposals(operation_id, repo_root)
        """.trimIndent(),
      )
    }
  }
}
