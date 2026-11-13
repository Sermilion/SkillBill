package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.core.schema.changeStatements
import skillbill.review.model.ReviewFindingActionKind
import java.sql.Connection

internal fun addReviewFindingActions(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS review_finding_actions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        review_run_id TEXT NOT NULL REFERENCES review_runs(review_run_id) ON DELETE CASCADE,
        kind TEXT NOT NULL CHECK (kind IN (${ReviewFindingActionKind.entries.joinToString { "'${it.wireValue}'" }})),
        started_at TEXT NOT NULL,
        finished_at TEXT,
        completed INTEGER NOT NULL DEFAULT 0,
        fixed INTEGER NOT NULL DEFAULT 0,
        open INTEGER NOT NULL DEFAULT 0,
        other INTEGER NOT NULL DEFAULT 0
      )
      """.trimIndent(),
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS review_finding_actions_run ON review_finding_actions (review_run_id, id)",
    )
  }
  installFindingChangeTriggers(connection) { row ->
    "SELECT invocation_id AS k FROM standalone_phase_status WHERE review_run_id = $row.review_run_id"
  }
}

internal fun installFindingChangeTriggers(
  connection: Connection,
  keys: (String) -> String,
) {
  connection.createStatement().use { statement ->
    listOf("review_finding_actions", "feedback_events").forEach { table ->
      listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
        statement.execute("DROP TRIGGER IF EXISTS desktop_change_${table}_${operation.lowercase()}")
        val row = if (operation == "DELETE") "OLD" else "NEW"
        val body = changeStatements(DesktopReadSchema.PHASES_TOPIC, keys(row))
        statement.execute(
          "CREATE TRIGGER desktop_change_${table}_${operation.lowercase()} AFTER $operation ON $table BEGIN $body END",
        )
      }
    }
  }
}
