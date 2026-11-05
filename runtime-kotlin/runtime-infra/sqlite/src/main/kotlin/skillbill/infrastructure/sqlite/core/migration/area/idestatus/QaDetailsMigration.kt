package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.schema.reinstallStandalonePhaseStatusChangeTriggers
import java.sql.Connection

internal const val QA_DETAILS_COLUMN: String = "qa_details"

internal const val QA_DETAILS_ASSIGNMENT: String = "$QA_DETAILS_COLUMN = COALESCE(?, $QA_DETAILS_COLUMN)"

internal fun recordQaDetails(connection: Connection) {
  listOf("feature_task_qa_outcomes", "standalone_phase_status").forEach { table ->
    DatabaseColumnMigrations.ensureColumn(
      connection,
      table,
      QA_DETAILS_COLUMN,
      "TEXT CHECK ($QA_DETAILS_COLUMN IS NULL OR json_valid($QA_DETAILS_COLUMN))",
    )
  }
  reinstallStandalonePhaseStatusChangeTriggers(connection)
}
