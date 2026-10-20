package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandalonePlanSubtask
import java.sql.Connection
import java.sql.ResultSet

internal const val PLAN_REPORT_COLUMN: String = "plan_report"

internal const val PLAN_REPORT_ASSIGNMENT: String = "$PLAN_REPORT_COLUMN = COALESCE(?, $PLAN_REPORT_COLUMN)"

internal object SqliteStandalonePlanReportKeys {
  const val FEATURE_NAME: String = "feature_name"
  const val PARENT_SPEC_PATH: String = "parent_spec_path"
  const val SUBTASKS: String = "subtasks"
  const val ID: String = "id"
  const val NAME: String = "name"
  const val SPEC_PATH: String = "spec_path"
  const val DEPENDS_ON: String = "depends_on"
}

internal fun recordStandalonePlanReport(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "standalone_phase_status",
    PLAN_REPORT_COLUMN,
    "TEXT CHECK ($PLAN_REPORT_COLUMN IS NULL OR json_valid($PLAN_REPORT_COLUMN))",
  )
}

internal fun StandalonePlanReport?.columnValue(): String? = this?.let(::encodeStandalonePlanReport)

internal fun encodeStandalonePlanReport(report: StandalonePlanReport): String =
  JsonCodec.valueToJsonString(
    mapOf(
      SqliteStandalonePlanReportKeys.FEATURE_NAME to report.featureName,
      SqliteStandalonePlanReportKeys.PARENT_SPEC_PATH to report.parentSpecPath,
      SqliteStandalonePlanReportKeys.SUBTASKS to
        report.subtasks.map {
          mapOf(
            SqliteStandalonePlanReportKeys.ID to it.id,
            SqliteStandalonePlanReportKeys.NAME to it.name,
            SqliteStandalonePlanReportKeys.SPEC_PATH to it.specPath,
            SqliteStandalonePlanReportKeys.DEPENDS_ON to it.dependsOn,
          )
        },
    ),
  )

internal fun decodeStandalonePlanReport(
  json: String,
  onInvalid: (String) -> Nothing,
): StandalonePlanReport {
  val report = JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(json)) ?: onInvalid("Not a plan report object.")
  return StandalonePlanReport(
    featureName = report[SqliteStandalonePlanReportKeys.FEATURE_NAME] as? String ?: onInvalid("No feature name."),
    parentSpecPath =
      report[SqliteStandalonePlanReportKeys.PARENT_SPEC_PATH] as? String ?: onInvalid("No parent spec path."),
    subtasks =
      JsonCodec.anyToStringAnyMapList(report[SqliteStandalonePlanReportKeys.SUBTASKS]).orEmpty().map { subtask ->
        StandalonePlanSubtask(
          id = (subtask[SqliteStandalonePlanReportKeys.ID] as? Number)?.toInt() ?: onInvalid("A subtask has no id."),
          name = subtask[SqliteStandalonePlanReportKeys.NAME] as? String ?: onInvalid("A subtask has no name."),
          specPath = (subtask[SqliteStandalonePlanReportKeys.SPEC_PATH] as? String).orEmpty(),
          dependsOn =
            (subtask[SqliteStandalonePlanReportKeys.DEPENDS_ON] as? List<*>).orEmpty().map {
              (it as? Number)?.toInt() ?: onInvalid("A subtask dependency is not a number.")
            },
        )
      },
  )
}

internal fun ResultSet.readPlanReport(sourceLabel: String): StandalonePlanReport? {
  val raw = getString(PLAN_REPORT_COLUMN) ?: return null
  return decodeStandalonePlanReport(raw) { reason ->
    throw invalidIdeStatusSchemaError(sourceLabel, PLAN_REPORT_COLUMN, reason)
  }
}
