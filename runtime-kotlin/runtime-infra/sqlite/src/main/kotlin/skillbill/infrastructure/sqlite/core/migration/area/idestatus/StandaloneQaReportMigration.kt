package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection
import java.sql.ResultSet

internal const val QA_REPORT_COLUMN: String = "qa_report"

internal const val QA_REPORT_TEXT_COLUMN: String = "qa_report_text"

internal const val QA_REPORT_TEXT_ASSIGNMENT: String = "$QA_REPORT_TEXT_COLUMN = COALESCE(?, $QA_REPORT_TEXT_COLUMN)"

internal fun recordStandaloneQaReport(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "standalone_phase_status",
    QA_REPORT_COLUMN,
    "TEXT CHECK ($QA_REPORT_COLUMN IS NULL OR json_valid($QA_REPORT_COLUMN))",
  )
}

internal fun recordQaReportText(connection: Connection) {
  listOf("feature_task_qa_outcomes", "standalone_phase_status").forEach { table ->
    DatabaseColumnMigrations.ensureColumn(connection, table, QA_REPORT_TEXT_COLUMN, "TEXT")
  }
}

internal fun ResultSet.readQaReportText(): String? = getString(QA_REPORT_TEXT_COLUMN)
