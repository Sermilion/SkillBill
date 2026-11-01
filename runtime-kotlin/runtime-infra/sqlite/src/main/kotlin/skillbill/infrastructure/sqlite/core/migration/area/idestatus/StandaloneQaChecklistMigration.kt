package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QA_CHECKLIST_CONTRACT_VERSION
import skillbill.contracts.qa.QA_CHECKLIST_MAX_ENCODED_BYTES
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.featuretask.qa.isQaProgressFailure
import skillbill.error.featuretask.qa.qaProgressFailed
import skillbill.error.featuretask.qa.qaProgressReason
import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.schema.reinstallStandalonePhaseStatusChangeTriggers
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistCase
import skillbill.ports.idestatus.model.StandaloneQaChecklistFinding
import skillbill.ports.idestatus.model.StandaloneQaChecklistStep
import skillbill.ports.idestatus.model.StandaloneQaChecklistTarget
import skillbill.ports.idestatus.model.StandaloneQaChecklistWriteResult
import skillbill.ports.idestatus.model.StandaloneQaStepStatus
import skillbill.ports.idestatus.model.StandaloneQaTargetState
import skillbill.workflow.time.parsePersistedInstantOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import skillbill.contracts.qa.QaChecklistPayloadKeys as Keys

internal const val QA_CHECKLIST_COLUMN: String = "qa_checklist"

internal fun recordStandaloneQaChecklist(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "standalone_phase_status",
    QA_CHECKLIST_COLUMN,
    "TEXT CHECK ($QA_CHECKLIST_COLUMN IS NULL OR json_valid($QA_CHECKLIST_COLUMN))",
  )
  reinstallStandalonePhaseStatusChangeTriggers(connection)
}

internal fun encodeStandaloneQaChecklist(checklist: StandaloneQaChecklist): String =
  JsonCodec.valueToJsonString(
    compact(
      Keys.CONTRACT_VERSION to QA_CHECKLIST_CONTRACT_VERSION,
      Keys.INVOCATION_DIRECTORY to checklist.invocationDirectory,
      Keys.REPORT_PATH to checklist.reportPath,
      Keys.TESTED_COMMIT to checklist.testedCommit,
      Keys.CRITERIA_SOURCE to checklist.criteriaSource,
      Keys.TARGETS to checklist.targets.map(::encodeTarget),
      Keys.PLAN_REGISTERED_AT to checklist.planRegisteredAt?.toString(),
      Keys.CASES to checklist.cases.map(::encodeCase),
      Keys.FINDINGS to checklist.findings.map(::encodeFinding),
      Keys.LAST_STEP_UPDATE_AT to checklist.lastStepUpdateAt?.toString(),
    ),
  )

private fun encodeTarget(target: StandaloneQaChecklistTarget): Map<String, Any?> =
  compact(
    Keys.NAME to target.name,
    Keys.STATE to target.state.wireValue,
    Keys.DETAIL to target.detail,
    Keys.UPDATED_AT to target.updatedAt?.toString(),
  )

private fun encodeCase(case: StandaloneQaChecklistCase): Map<String, Any?> =
  mapOf(Keys.ID to case.id, Keys.TITLE to case.title, Keys.STEPS to case.steps.map(::encodeStep))

private fun encodeStep(step: StandaloneQaChecklistStep): Map<String, Any?> =
  compact(
    Keys.TEXT to step.text,
    Keys.STATUS to step.status.wireValue,
    Keys.NOTE to step.note,
    Keys.FINDING_ID to step.findingId,
    Keys.EVIDENCE to step.evidence,
    Keys.UPDATED_AT to step.updatedAt?.toString(),
  )

private fun encodeFinding(finding: StandaloneQaChecklistFinding): Map<String, Any?> =
  mapOf(Keys.ID to finding.id, Keys.TITLE to finding.title, Keys.RECORDED_AT to finding.recordedAt.toString())

private fun compact(vararg entries: Pair<String, Any?>): Map<String, Any?> =
  entries.filter { (_, value) -> value != null }.toMap()

internal fun decodeStandaloneQaChecklist(json: String): StandaloneQaChecklist {
  val root = objectOf(JsonCodec.parseValue(json), Keys.CHECKLIST_FIELDS)
  if (root[Keys.CONTRACT_VERSION] != QA_CHECKLIST_CONTRACT_VERSION) invalid("the contract version is unknown")
  return StandaloneQaChecklist(
    invocationDirectory = optionalText(root, Keys.INVOCATION_DIRECTORY),
    reportPath = optionalText(root, Keys.REPORT_PATH),
    testedCommit = optionalText(root, Keys.TESTED_COMMIT),
    criteriaSource = optionalText(root, Keys.CRITERIA_SOURCE),
    targets = listAt(root, Keys.TARGETS).map(::decodeTarget),
    planRegisteredAt = optionalInstant(root, Keys.PLAN_REGISTERED_AT),
    cases = listAt(root, Keys.CASES).map(::decodeCase),
    findings = listAt(root, Keys.FINDINGS).map(::decodeFinding),
    lastStepUpdateAt = optionalInstant(root, Keys.LAST_STEP_UPDATE_AT),
  )
}

private fun decodeTarget(value: Any?): StandaloneQaChecklistTarget {
  val target = objectOf(value, Keys.TARGET_FIELDS)
  return StandaloneQaChecklistTarget(
    name = text(target, Keys.NAME),
    state = StandaloneQaTargetState.fromWire(target[Keys.STATE] as? String) ?: invalid("a target state is unknown"),
    detail = optionalText(target, Keys.DETAIL),
    updatedAt = optionalInstant(target, Keys.UPDATED_AT),
  )
}

private fun decodeCase(value: Any?): StandaloneQaChecklistCase {
  val case = objectOf(value, Keys.CASE_FIELDS)
  return StandaloneQaChecklistCase(
    id = text(case, Keys.ID),
    title = text(case, Keys.TITLE),
    steps = listAt(case, Keys.STEPS).map(::decodeStep),
  )
}

private fun decodeStep(value: Any?): StandaloneQaChecklistStep {
  val step = objectOf(value, Keys.STEP_FIELDS)
  return StandaloneQaChecklistStep(
    text = text(step, Keys.TEXT),
    status = StandaloneQaStepStatus.fromWire(step[Keys.STATUS] as? String) ?: invalid("a step status is unknown"),
    note = optionalText(step, Keys.NOTE),
    findingId = optionalText(step, Keys.FINDING_ID),
    evidence = listAt(step, Keys.EVIDENCE).map { it as? String ?: invalid("an evidence path is not a string") },
    updatedAt = optionalInstant(step, Keys.UPDATED_AT),
  )
}

private fun decodeFinding(value: Any?): StandaloneQaChecklistFinding {
  val finding = objectOf(value, Keys.FINDING_FIELDS)
  return StandaloneQaChecklistFinding(
    id = text(finding, Keys.ID),
    title = text(finding, Keys.TITLE),
    recordedAt = optionalInstant(finding, Keys.RECORDED_AT) ?: invalid("a finding has no time"),
  )
}

private fun objectOf(
  value: Any?,
  fields: Set<String>,
): Map<String, Any?> {
  val map = JsonCodec.anyToStringAnyMap(value) ?: invalid("a value is not an object")
  if (!fields.containsAll(map.keys)) invalid("an object has an unknown key")
  return map
}

private fun listAt(
  parent: Map<String, Any?>,
  key: String,
): List<Any?> = parent[key] as? List<*> ?: invalid("a required list is missing")

private fun text(
  parent: Map<String, Any?>,
  key: String,
): String = parent[key] as? String ?: invalid("a required text value is missing")

private fun optionalText(
  parent: Map<String, Any?>,
  key: String,
): String? = parent[key]?.let { it as? String ?: invalid("a text value has the wrong type") }

private fun optionalInstant(
  parent: Map<String, Any?>,
  key: String,
): Instant? = optionalText(parent, key)?.let { parsePersistedInstantOrNull(it) ?: invalid("a time is invalid") }

private fun invalid(reason: String): Nothing = throw qaProgressFailed(reason)

internal fun ResultSet.readQaChecklist(
  invocationId: String,
  diagnostics: RuntimeDiagnostics,
): StandaloneQaChecklist? {
  val raw = getString(QA_CHECKLIST_COLUMN) ?: return null
  return catchingNonCancellation { decodeStandaloneQaChecklist(raw) }
    .getOrElse { failure ->
      val reason =
        (failure as? SkillBillRuntimeException)?.takeIf { it.isQaProgressFailure }?.qaProgressReason
          ?: "the stored checklist is not valid JSON"
      diagnostics.warning("seam=qa_progress kind=unreadable invocation=$invocationId reason=$reason")
      null
    }
}

internal fun Connection.writeStandaloneQaChecklist(
  invocationId: String,
  checklist: StandaloneQaChecklist,
): StandaloneQaChecklistWriteResult {
  val encoded = encodeStandaloneQaChecklist(checklist)
  if (encoded.toByteArray().size > QA_CHECKLIST_MAX_ENCODED_BYTES) {
    throw qaProgressFailed("the encoded checklist exceeds $QA_CHECKLIST_MAX_ENCODED_BYTES bytes")
  }
  val updated =
    prepareStatement(
      "UPDATE standalone_phase_status SET $QA_CHECKLIST_COLUMN = ? WHERE invocation_id = ? " +
        "AND finished_at IS NULL AND lifecycle_state IN ('active', 'paused')",
    ).use { statement ->
      statement.setString(1, encoded)
      statement.setString(2, invocationId)
      statement.executeUpdate()
    }
  if (updated > 0) return StandaloneQaChecklistWriteResult.WRITTEN
  val exists =
    prepareStatement("SELECT 1 FROM standalone_phase_status WHERE invocation_id = ?").use { statement ->
      statement.setString(1, invocationId)
      statement.executeQuery().use { rows -> rows.next() }
    }
  return if (exists) StandaloneQaChecklistWriteResult.FINISHED else StandaloneQaChecklistWriteResult.MISSING
}
