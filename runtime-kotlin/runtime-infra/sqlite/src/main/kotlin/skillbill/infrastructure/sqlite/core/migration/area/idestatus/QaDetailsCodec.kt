package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.contracts.JsonCodec
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.core.catchingNonCancellation
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.qa.QaAssertionOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunAssertion
import skillbill.workflow.taskruntime.model.qa.QaRunCoverage
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunFinding
import skillbill.workflow.taskruntime.model.qa.QaRunRetainedResource
import skillbill.workflow.taskruntime.model.qa.QaRunTemporaryEdit
import java.sql.ResultSet

internal fun encodeQaDetails(details: QaRunDetails): String =
  JsonCodec.valueToJsonString(
    mapOf(
      SqliteQaDetailsKeys.REPORT_PATH to details.reportPath,
      SqliteQaDetailsKeys.INVOCATION_DIRECTORY to details.invocationDirectory,
      SqliteQaDetailsKeys.SKIP_REASON to details.skipReason,
      SqliteQaDetailsKeys.ASSERTIONS to details.assertions.map(::assertionEntry),
      SqliteQaDetailsKeys.COVERAGE to details.coverage.map(::coverageEntry),
      SqliteQaDetailsKeys.RETAINED_RESOURCES to
        details.retainedResources.map { resource ->
          mapOf(SqliteQaDetailsKeys.KIND to resource.kind, SqliteQaDetailsKeys.IDENTITY to resource.identity)
        },
      SqliteQaDetailsKeys.TEMPORARY_EDITS to details.temporaryEdits.map(::temporaryEditEntry),
      SqliteQaDetailsKeys.FINDINGS to details.findings.map(::findingEntry),
    ),
  )

internal fun decodeQaDetails(json: String): QaRunDetails {
  val parsed =
    try {
      JsonCodec.parseValue(json)
    } catch (_: MalformedJsonTextError) {
      invalidQaDetails("Stored QA details are not valid JSON.")
    }
  val root = JsonCodec.anyToStringAnyMap(parsed) ?: invalidQaDetails("Stored QA details are not an object.")
  return QaRunDetails(
    reportPath = root.optionalText(SqliteQaDetailsKeys.REPORT_PATH),
    invocationDirectory = root.optionalText(SqliteQaDetailsKeys.INVOCATION_DIRECTORY),
    skipReason = root.optionalText(SqliteQaDetailsKeys.SKIP_REASON),
    assertions = root.objectList(SqliteQaDetailsKeys.ASSERTIONS).map(::decodeAssertion),
    coverage = root.objectList(SqliteQaDetailsKeys.COVERAGE).map(::decodeCoverage),
    retainedResources =
      root.objectList(SqliteQaDetailsKeys.RETAINED_RESOURCES).map { entry ->
        QaRunRetainedResource(
          entry.requiredText(SqliteQaDetailsKeys.KIND),
          entry.requiredText(SqliteQaDetailsKeys.IDENTITY),
        )
      },
    temporaryEdits = root.objectList(SqliteQaDetailsKeys.TEMPORARY_EDITS).map(::decodeTemporaryEdit),
    findings = root.objectList(SqliteQaDetailsKeys.FINDINGS).map(::decodeFinding),
  )
}

internal fun ResultSet.readQaDetailsOrWarn(
  invocationId: String,
  diagnostics: RuntimeDiagnostics,
): QaRunDetails? {
  val raw = getString(QA_DETAILS_COLUMN) ?: return null
  return catchingNonCancellation { decodeQaDetails(raw) }
    .getOrElse { failure ->
      diagnostics.warning("seam=qa_details kind=unreadable invocation=$invocationId reason=${failure.message}")
      null
    }
}

private fun assertionEntry(assertion: QaRunAssertion): Map<String, Any?> =
  mapOf(
    SqliteQaDetailsKeys.OUTCOME to assertion.outcome.wireValue,
    SqliteQaDetailsKeys.TITLE to assertion.title,
    SqliteQaDetailsKeys.DETAIL to assertion.detail,
    SqliteQaDetailsKeys.EVIDENCE_PATHS to assertion.evidencePaths,
  )

private fun coverageEntry(coverage: QaRunCoverage): Map<String, Any?> =
  mapOf(
    SqliteQaDetailsKeys.KIND to coverage.kind,
    SqliteQaDetailsKeys.VERDICT to coverage.verdict,
    SqliteQaDetailsKeys.REASON to coverage.reason,
    SqliteQaDetailsKeys.BACKEND to coverage.backend,
  )

private fun temporaryEditEntry(edit: QaRunTemporaryEdit): Map<String, Any?> =
  mapOf(
    SqliteQaDetailsKeys.PATH to edit.path,
    SqliteQaDetailsKeys.DETAIL to edit.detail,
    SqliteQaDetailsKeys.NOT_RESTORED to edit.notRestored,
  )

private fun findingEntry(finding: QaRunFinding): Map<String, Any?> =
  mapOf(
    SqliteQaDetailsKeys.ID to finding.id,
    SqliteQaDetailsKeys.TITLE to finding.title,
    SqliteQaDetailsKeys.OBSERVED to finding.observed,
    SqliteQaDetailsKeys.EXPECTED to finding.expected,
    SqliteQaDetailsKeys.STEPS to finding.steps,
    SqliteQaDetailsKeys.EVIDENCE_PATHS to finding.evidencePaths,
  )

private fun decodeAssertion(entry: Map<String, Any?>): QaRunAssertion {
  val raw = entry.requiredText(SqliteQaDetailsKeys.OUTCOME)
  return QaRunAssertion(
    QaAssertionOutcome.fromWire(raw) ?: invalidQaDetails("Stored QA assertion outcome '$raw' is not known."),
    entry.requiredText(SqliteQaDetailsKeys.TITLE),
    entry.requiredText(SqliteQaDetailsKeys.DETAIL),
    entry.textList(SqliteQaDetailsKeys.EVIDENCE_PATHS),
  )
}

private fun decodeCoverage(entry: Map<String, Any?>): QaRunCoverage =
  QaRunCoverage(
    entry.requiredText(SqliteQaDetailsKeys.KIND),
    entry.requiredText(SqliteQaDetailsKeys.VERDICT),
    entry.optionalText(SqliteQaDetailsKeys.REASON),
    entry.optionalText(SqliteQaDetailsKeys.BACKEND),
  )

private fun decodeTemporaryEdit(entry: Map<String, Any?>): QaRunTemporaryEdit =
  QaRunTemporaryEdit(
    entry.optionalText(SqliteQaDetailsKeys.PATH),
    entry.requiredText(SqliteQaDetailsKeys.DETAIL),
    when (val notRestored = entry[SqliteQaDetailsKeys.NOT_RESTORED]) {
      null -> false
      is Boolean -> notRestored
      else -> invalidQaDetails("Stored QA temporary edit has a non-boolean not_restored value.")
    },
  )

private fun decodeFinding(entry: Map<String, Any?>): QaRunFinding =
  QaRunFinding(
    entry.requiredText(SqliteQaDetailsKeys.ID),
    entry.requiredText(SqliteQaDetailsKeys.TITLE),
    entry.optionalText(SqliteQaDetailsKeys.OBSERVED),
    entry.optionalText(SqliteQaDetailsKeys.EXPECTED),
    entry.optionalText(SqliteQaDetailsKeys.STEPS),
    entry.textList(SqliteQaDetailsKeys.EVIDENCE_PATHS),
  )

private fun Map<String, Any?>.requiredText(key: String): String =
  this[key] as? String ?: invalidQaDetails("Stored QA details have no text value for '$key'.")

private fun Map<String, Any?>.optionalText(key: String): String? =
  when (val value = this[key]) {
    null -> null
    is String -> value
    else -> invalidQaDetails("Stored QA details have a non-text value for '$key'.")
  }

private fun Map<String, Any?>.textList(key: String): List<String> =
  this[key]?.let { value ->
    JsonCodec.anyToStringList(value) ?: invalidQaDetails("Stored QA details have a non-text list for '$key'.")
  }.orEmpty()

private fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>> =
  this[key]?.let { value ->
    JsonCodec.anyToStringAnyMapList(value) ?: invalidQaDetails("Stored QA details have a non-object list for '$key'.")
  }.orEmpty()

private fun invalidQaDetails(reason: String): Nothing = throw invalidWorkflowStateSchemaError(reason)
