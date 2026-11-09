package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QA_PANEL_HISTORY_CONTRACT_VERSION
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.core.catchingNonCancellation
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.qa.QaAssertionOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunAssertion
import skillbill.workflow.taskruntime.model.qa.QaRunCoverage
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunFinding
import skillbill.workflow.taskruntime.model.qa.QaRunOccurrence
import skillbill.workflow.taskruntime.model.qa.QaRunPreview
import skillbill.workflow.taskruntime.model.qa.QaRunRetainedResource
import skillbill.workflow.taskruntime.model.qa.QaRunScreenshot
import skillbill.workflow.taskruntime.model.qa.QaRunStage
import skillbill.workflow.taskruntime.model.qa.QaRunTemporaryEdit
import java.sql.ResultSet

internal fun encodeQaDetails(details: QaRunDetails): String =
  JsonCodec.valueToJsonString(
    mapOf(
      SqliteQaDetailsKeys.PREVIEW to
        details.preview?.let { preview ->
          mapOf(
            SqliteQaDetailsKeys.INVOCATION_ID to preview.invocationId,
            SqliteQaDetailsKeys.INVOCATION_DIRECTORY to preview.invocationDirectory,
            SqliteQaDetailsKeys.TARGETS to preview.targets,
            SqliteQaDetailsKeys.ACTIVE to preview.active,
          )
        },
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
      SqliteQaDetailsKeys.HISTORY_AVAILABILITY to details.historyAvailability,
      SqliteQaDetailsKeys.HISTORY_CONTRACT_VERSION to details.historyContractVersion,
      SqliteQaDetailsKeys.OCCURRENCES to details.occurrences.map(::occurrenceEntry),
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
  val historyVersion = root[SqliteQaDetailsKeys.HISTORY_CONTRACT_VERSION]
  if (historyVersion != null && historyVersion !is String) {
    invalidQaDetails("Stored QA panel history contract version is not text.")
  }
  if (historyVersion != null && historyVersion != QA_PANEL_HISTORY_CONTRACT_VERSION) {
    invalidQaDetails(
      "Stored QA panel history contract '$historyVersion' is unsupported; preserve the database and migrate it.",
    )
  }
  return QaRunDetails(
    preview =
      root[SqliteQaDetailsKeys.PREVIEW]?.let { value ->
        val entry = JsonCodec.anyToStringAnyMap(value) ?: invalidQaDetails("Stored QA preview is not an object.")
        QaRunPreview(
          entry.requiredText(SqliteQaDetailsKeys.INVOCATION_ID),
          entry.requiredText(SqliteQaDetailsKeys.INVOCATION_DIRECTORY),
          entry.textList(SqliteQaDetailsKeys.TARGETS),
          entry[SqliteQaDetailsKeys.ACTIVE] as? Boolean ?: invalidQaDetails("Stored QA preview has no active flag."),
        )
      },
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
    historyAvailability = root.optionalText(SqliteQaDetailsKeys.HISTORY_AVAILABILITY) ?: "not_recorded",
    occurrences = root.objectList(SqliteQaDetailsKeys.OCCURRENCES).map(::decodeOccurrence),
    historyContractVersion = QA_PANEL_HISTORY_CONTRACT_VERSION,
  )
}

internal fun encodeQaOccurrence(occurrence: QaRunOccurrence): String =
  JsonCodec.valueToJsonString(occurrenceEntry(occurrence))

internal fun decodeQaOccurrence(json: String): QaRunOccurrence {
  val value = JsonCodec.parseValue(json)
  val entry = JsonCodec.anyToStringAnyMap(value) ?: invalidQaDetails("Stored QA occurrence is not an object.")
  return decodeOccurrence(entry)
}

private fun occurrenceEntry(occurrence: QaRunOccurrence): Map<String, Any?> =
  mapOf(
    SqliteQaDetailsKeys.OCCURRENCE_ID to occurrence.occurrenceId,
    SqliteQaDetailsKeys.KIND to occurrence.kind,
    SqliteQaDetailsKeys.PHASE_ATTEMPT to occurrence.phaseAttempt,
    SqliteQaDetailsKeys.WORKSPACE_INVOCATION_ID to occurrence.workspaceInvocationId,
    SqliteQaDetailsKeys.STARTED_AT to occurrence.startedAt,
    SqliteQaDetailsKeys.FINISHED_AT to occurrence.finishedAt,
    SqliteQaDetailsKeys.DISPOSITION to occurrence.disposition,
    SqliteQaDetailsKeys.TESTED_TREE_IDENTITY to occurrence.testedTreeIdentity,
    SqliteQaDetailsKeys.RESULT_REFERENCE to occurrence.resultReference,
    SqliteQaDetailsKeys.FRAME_REFERENCE to occurrence.frameReference,
    SqliteQaDetailsKeys.FRAME_AVAILABILITY to occurrence.frameAvailability,
    SqliteQaDetailsKeys.SCREENSHOTS to
      occurrence.screenshots.map { screenshot ->
        mapOf(
          SqliteQaDetailsKeys.LABEL to screenshot.label,
          SqliteQaDetailsKeys.PATH to screenshot.path,
          SqliteQaDetailsKeys.STARTED_AT to screenshot.recordedAt,
          SqliteQaDetailsKeys.FORMAT to screenshot.format,
        )
      },
    SqliteQaDetailsKeys.STAGES to
      occurrence.stages.map { stage ->
        mapOf(
          SqliteQaDetailsKeys.NAME to stage.name,
          SqliteQaDetailsKeys.STATE to stage.state,
          SqliteQaDetailsKeys.STARTED_AT to stage.startedAt,
          SqliteQaDetailsKeys.FINISHED_AT to stage.finishedAt,
          SqliteQaDetailsKeys.DETAIL to stage.detail,
        )
      },
  )

private fun decodeOccurrence(entry: Map<String, Any?>): QaRunOccurrence =
  QaRunOccurrence(
    occurrenceId = entry.requiredText(SqliteQaDetailsKeys.OCCURRENCE_ID),
    kind = entry.requiredText(SqliteQaDetailsKeys.KIND),
    phaseAttempt = (entry[SqliteQaDetailsKeys.PHASE_ATTEMPT] as? Number)?.toInt(),
    workspaceInvocationId = entry.optionalText(SqliteQaDetailsKeys.WORKSPACE_INVOCATION_ID),
    startedAt = entry.requiredText(SqliteQaDetailsKeys.STARTED_AT),
    finishedAt = entry.optionalText(SqliteQaDetailsKeys.FINISHED_AT),
    disposition = entry.optionalText(SqliteQaDetailsKeys.DISPOSITION) ?: "unknown",
    testedTreeIdentity = entry.optionalText(SqliteQaDetailsKeys.TESTED_TREE_IDENTITY),
    resultReference = entry.optionalText(SqliteQaDetailsKeys.RESULT_REFERENCE),
    frameReference = entry.optionalText(SqliteQaDetailsKeys.FRAME_REFERENCE),
    frameAvailability = entry.optionalText(SqliteQaDetailsKeys.FRAME_AVAILABILITY) ?: "unavailable",
    screenshots =
      entry.objectList(SqliteQaDetailsKeys.SCREENSHOTS).map { image ->
        QaRunScreenshot(
          image.requiredText(SqliteQaDetailsKeys.LABEL),
          image.requiredText(SqliteQaDetailsKeys.PATH),
          image.requiredText(SqliteQaDetailsKeys.STARTED_AT),
          image.requiredText(SqliteQaDetailsKeys.FORMAT),
        )
      },
    stages =
      entry.objectList(SqliteQaDetailsKeys.STAGES).map { stage ->
        QaRunStage(
          stage.requiredText(SqliteQaDetailsKeys.NAME),
          stage.requiredText(SqliteQaDetailsKeys.STATE),
          stage.optionalText(SqliteQaDetailsKeys.STARTED_AT),
          stage.optionalText(SqliteQaDetailsKeys.FINISHED_AT),
          stage.optionalText(SqliteQaDetailsKeys.DETAIL),
        )
      },
  )

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
