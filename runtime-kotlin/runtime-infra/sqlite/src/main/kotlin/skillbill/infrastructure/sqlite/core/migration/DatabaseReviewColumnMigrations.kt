package skillbill.infrastructure.sqlite.core.migration

import skillbill.infrastructure.sqlite.core.schema.DatabaseReviewLedgerSchema
import java.sql.Connection

internal object DatabaseReviewColumnMigrations {
  fun apply(connection: Connection) {
    ensureReviewRunLaneDispositionColumns(connection)
    DatabaseReviewFindingColumnMigrations.ensureFindingColumns(connection)
    ensureUnaddressedFindingColumns(connection)
    ensureReviewFindingOutcomeColumns(connection)
    ensureReviewStageStateTables(connection)
    ensureFindingVerdictReasonColumn(connection)
    DatabaseReviewFindingColumnMigrations.backfillReviewSessionIds(connection)
  }

  fun ensureFindingVerdictReasonColumn(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "review_run_finding_verdicts")) return
    DatabaseColumnMigrations.ensureColumn(connection, "review_run_finding_verdicts", "rejection_reason", "TEXT")
  }

  fun ensureReviewStageStateTables(connection: Connection) {
    DatabaseReviewLedgerSchema.reviewStageStateStatements.forEach { sql ->
      connection.createStatement().use { statement -> statement.execute(sql) }
    }
  }

  private fun ensureUnaddressedFindingColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "unaddressed_findings")) return
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "issue_key", "TEXT NOT NULL DEFAULT ''")
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "unaddressed_findings",
      "subtask_id",
      "INTEGER NOT NULL DEFAULT 0",
    )
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "severity", "TEXT NOT NULL DEFAULT ''")
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "unaddressed_findings",
      "issue_category",
      "TEXT NOT NULL DEFAULT 'other'",
    )
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "unaddressed_findings",
      "location",
      "TEXT NOT NULL DEFAULT '<unknown>'",
    )
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "summary", "TEXT NOT NULL DEFAULT ''")
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "recorded_at", "TEXT NOT NULL DEFAULT ''")
    ensureReviewFindingOutcomeKeyColumns(connection)
    ensureUnaddressedFindingVerdictColumns(connection)
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_unaddressed_findings_issue " +
          "ON unaddressed_findings(issue_key, subtask_id, review_pass_number)",
      )
    }
  }

  fun ensureUnaddressedFindingVerdictColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "unaddressed_findings")) return
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "claim_verdict", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "scope_disposition", "TEXT")
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "unaddressed_findings",
      "citations",
      "TEXT NOT NULL DEFAULT ''",
    )
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "unaddressed_findings",
      "severity_adjustment_direction",
      "TEXT",
    )
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "unaddressed_findings",
      "severity_adjustment_justification",
      "TEXT",
    )
  }

  fun ensureReviewFindingOutcomeKeyColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "unaddressed_findings")) return
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "review_run_id", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "finding_id", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "verification_disposition", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, "unaddressed_findings", "verification_reason", "TEXT")
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_unaddressed_findings_run " +
          "ON unaddressed_findings(review_run_id, finding_id)",
      )
    }
  }

  fun ensureReviewFindingOutcomeColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "review_finding_outcomes")) return
    DatabaseColumnMigrations.ensureColumn(connection, "review_finding_outcomes", "finding_key", "TEXT")
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_review_finding_outcomes_key " +
          "ON review_finding_outcomes(workflow_id, finding_key)",
      )
    }
  }

  fun ensureReviewRunLaneDispositionColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "review_run_lanes")) return
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "review_run_lanes",
      "review_disposition",
      "TEXT NOT NULL DEFAULT 'incomplete'",
    )
    DatabaseColumnMigrations.ensureColumn(connection, "review_run_lanes", "bundle_composition_digest", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, "review_run_lanes", "segment_accounting_json", "TEXT")
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "review_run_lanes",
      "unreviewed_segment_ids",
      "TEXT NOT NULL DEFAULT ''",
    )
    DatabaseColumnMigrations.ensureColumn(connection, "review_run_lanes", "budget_dimension", "TEXT")
  }
}
