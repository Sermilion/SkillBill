package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.ports.idestatus.model.StandaloneQaCheck
import skillbill.ports.idestatus.model.StandaloneQaCriterion
import skillbill.ports.idestatus.model.StandaloneQaCriterionFinding
import skillbill.ports.idestatus.model.StandaloneQaCriterionStatus
import skillbill.ports.idestatus.model.StandaloneQaReport
import java.sql.Connection
import java.sql.ResultSet

internal const val QA_REPORT_COLUMN: String = "qa_report"

internal const val QA_REPORT_ASSIGNMENT: String = "$QA_REPORT_COLUMN = COALESCE(?, $QA_REPORT_COLUMN)"

internal object SqliteStandaloneQaReportKeys {
  const val CHECKS: String = "checks"
  const val PASSED: String = "passed"
}

internal fun recordStandaloneQaReport(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "standalone_phase_status",
    QA_REPORT_COLUMN,
    "TEXT CHECK ($QA_REPORT_COLUMN IS NULL OR json_valid($QA_REPORT_COLUMN))",
  )
}

internal fun StandaloneQaReport?.columnValue(): String? = this?.let(::encodeStandaloneQaReport)

internal fun encodeStandaloneQaReport(report: StandaloneQaReport): String =
  JsonCodec.valueToJsonString(
    report.criteria.map { criterion ->
      mapOf(
        QaReceiptPayloadKeys.ID to criterion.id,
        QaReceiptPayloadKeys.STATUS to criterion.status.wireValue,
        SqliteStandaloneQaReportKeys.CHECKS to
          criterion.checks.map {
            mapOf(
              QaReceiptPayloadKeys.DESCRIPTION to it.description,
              SqliteStandaloneQaReportKeys.PASSED to it.passed,
            )
          },
        QaReceiptPayloadKeys.FINDINGS to
          criterion.findings.map {
            mapOf(
              QaReceiptPayloadKeys.ID to it.id,
              QaReceiptPayloadKeys.STEPS to it.steps,
              QaReceiptPayloadKeys.EXPECTED to it.expected,
              QaReceiptPayloadKeys.OBSERVED to it.observed,
            )
          },
      )
    },
  )

internal fun decodeStandaloneQaReport(
  json: String,
  onInvalid: (String) -> Nothing,
): StandaloneQaReport {
  val criteria = JsonCodec.anyToStringAnyMapList(JsonCodec.parseValue(json)) ?: onInvalid("Not a list of criteria.")
  return StandaloneQaReport(
    criteria.map { criterion ->
      val status = criterion[QaReceiptPayloadKeys.STATUS] as? String
      StandaloneQaCriterion(
        id = criterion[QaReceiptPayloadKeys.ID] as? String,
        status = StandaloneQaCriterionStatus.fromWire(status) ?: onInvalid("Unknown criterion status '$status'."),
        checks =
          JsonCodec.anyToStringAnyMapList(criterion[SqliteStandaloneQaReportKeys.CHECKS]).orEmpty().map {
            StandaloneQaCheck(
              (it[QaReceiptPayloadKeys.DESCRIPTION] as? String).orEmpty(),
              it[SqliteStandaloneQaReportKeys.PASSED] == true,
            )
          },
        findings =
          JsonCodec.anyToStringAnyMapList(criterion[QaReceiptPayloadKeys.FINDINGS]).orEmpty().map {
            StandaloneQaCriterionFinding(
              id = it[QaReceiptPayloadKeys.ID] as? String ?: onInvalid("A criterion finding has no id."),
              steps = JsonCodec.anyToStringList(it[QaReceiptPayloadKeys.STEPS]).orEmpty(),
              expected = (it[QaReceiptPayloadKeys.EXPECTED] as? String).orEmpty(),
              observed = (it[QaReceiptPayloadKeys.OBSERVED] as? String).orEmpty(),
            )
          },
      )
    },
  )
}

internal fun ResultSet.readQaReport(sourceLabel: String): StandaloneQaReport? {
  val raw = getString(QA_REPORT_COLUMN) ?: return null
  return decodeStandaloneQaReport(raw) { reason ->
    throw invalidIdeStatusSchemaError(sourceLabel, QA_REPORT_COLUMN, reason)
  }
}
