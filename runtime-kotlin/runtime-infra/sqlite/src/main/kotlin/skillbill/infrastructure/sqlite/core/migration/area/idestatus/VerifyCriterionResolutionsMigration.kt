package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.migration.area.installFindingChangeTriggers
import skillbill.infrastructure.sqlite.core.schema.installLaterDesktopChangeTriggers
import java.sql.Connection

internal const val VERIFY_CRITERION_RESOLUTIONS_TABLE: String = "verify_criterion_resolutions"

internal fun verifyCriterionResolutionsMigration(): DatabaseMigration =
  DatabaseMigration(
    version = 128,
    name = "record-verify-criterion-resolutions",
    operation = ::recordVerifyCriterionResolutions,
  )

internal fun recordVerifyCriterionResolutions(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS $VERIFY_CRITERION_RESOLUTIONS_TABLE (
        workflow_id TEXT NOT NULL REFERENCES feature_verify_workflows(workflow_id) ON DELETE CASCADE,
        criterion_number INTEGER NOT NULL CHECK (criterion_number > 0),
        evidence TEXT NOT NULL CHECK (trim(evidence) != ''),
        recorded_at TEXT NOT NULL,
        PRIMARY KEY (workflow_id, criterion_number)
      )
      """.trimIndent(),
    )
  }
  installLaterDesktopChangeTriggers(connection, setOf(VERIFY_CRITERION_RESOLUTIONS_TABLE))
  installFindingChangeTriggers(connection) { row ->
    "SELECT invocation_id AS k FROM standalone_phase_status WHERE review_run_id = $row.review_run_id " +
      "UNION SELECT verify.workflow_id AS k FROM feature_verify_workflows AS verify " +
      "JOIN review_runs AS runs ON runs.review_run_id = $row.review_run_id " +
      "WHERE (verify.session_id != '' AND verify.session_id = runs.review_session_id) " +
      "OR verify.workflow_id = runs.review_session_id"
  }
}
