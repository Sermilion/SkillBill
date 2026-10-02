package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleSessionCompletion
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.sqlite.core.ops.degradedValuePreview
import skillbill.infrastructure.sqlite.core.ops.recordDegradedValue
import skillbill.infrastructure.sqlite.telemetry.goal.GoalTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.telemetry.redaction.redactIssueKey
import skillbill.infrastructure.sqlite.telemetry.redaction.redactIssueKeyReferences
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.attribution.normalizeRoutedSkill
import skillbill.review.attribution.normalizeStackLabel
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys as LifeKeys
import skillbill.infrastructure.sqlite.telemetry.SqliteReviewTelemetryPayloadKeys as RevTelKeys
import skillbill.infrastructure.sqlite.telemetry.lifecycle.SqliteLifecycleTelemetryMaterializationPayloadKeys as MatKeys

internal fun featureTaskRuntimeStartedPayload(
  row: Map<String, Any?>,
  level: String,
  salt: String,
): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    LifeKeys.SESSION_ID to row.stringOrEmpty(LifeKeys.SESSION_ID),
    MatKeys.FEATURE_SIZE to row.stringOrEmpty(MatKeys.FEATURE_SIZE),
    SharedPayloadKeys.ISSUE_KEY to redactIssueKey(row.stringOrEmpty(SharedPayloadKeys.ISSUE_KEY), level, salt),
  ).apply {
    putAll(correlationFields(row, level, salt))
    if (level == "full") {
      put(GoalTelemetryPayloadKeys.FEATURE_NAME, row.stringOrEmpty(GoalTelemetryPayloadKeys.FEATURE_NAME))
    }
  }

private fun correlationFields(
  row: Map<String, Any?>,
  level: String,
  salt: String,
): Map<String, Any?> {
  val workflowId = row.stringOrEmpty(SharedPayloadKeys.WORKFLOW_ID)
  val issueKey = row.stringOrEmpty(SharedPayloadKeys.ISSUE_KEY)
  val parentWorkflowId = row.stringOrEmpty(LifeKeys.GOAL_PARENT_WORKFLOW_ID)
  val availability =
    if (workflowId.isBlank()) {
      TelemetryMeasurementAvailability.UNKNOWN
    } else {
      TelemetryMeasurementAvailability.MEASURED
    }
  return linkedMapOf(
    LifeKeys.CORRELATION_AVAILABILITY to availability.wireValue,
    LifeKeys.REDACTED_WORKFLOW_ID to
      workflowId.takeIf { it.isNotBlank() }?.let { redactIssueKeyReferences(it, issueKey, level, salt) },
    LifeKeys.GOAL_PARENT_WORKFLOW_ID to
      parentWorkflowId.takeIf { it.isNotBlank() }?.let { redactIssueKeyReferences(it, issueKey, level, salt) },
    LifeKeys.GOAL_SUBTASK_ID to row.nullableInt(LifeKeys.GOAL_SUBTASK_ID),
  )
}

internal fun featureTaskRuntimeFinishedPayload(
  row: Map<String, Any?>,
  level: String,
  salt: String,
  diagnostics: RuntimeDiagnostics,
): Map<String, Any?> =
  linkedMapOf<String, Any?>(LifeKeys.SESSION_ID to row.stringOrEmpty(LifeKeys.SESSION_ID)).apply {
    putAll(correlationFields(row, level, salt))
    put(LifeKeys.COMPLETION_STATUS, row.stringOrEmpty(LifeKeys.COMPLETION_STATUS))
    put(
      RevTelKeys.COMPLETED_PHASE_IDS,
      parseStoredJsonArray(
        row.stringOrEmpty(RevTelKeys.COMPLETED_PHASE_IDS),
        RevTelKeys.COMPLETED_PHASE_IDS,
        diagnostics,
      ),
    )
    put(MatKeys.PHASE_OUTCOMES, parsePhaseOutcomes(row.stringOrEmpty(MatKeys.PHASE_OUTCOMES)))
    put(MatKeys.REVIEW_FIX_ITERATION_COUNT, row.intOrZero(MatKeys.REVIEW_FIX_ITERATION_COUNT))
    put(MatKeys.FINDING_VERIFICATION_VERIFIED_COUNT, row.intOrZero(MatKeys.FINDING_VERIFICATION_VERIFIED_COUNT))
    put(MatKeys.FINDING_VERIFICATION_REJECTED_COUNT, row.intOrZero(MatKeys.FINDING_VERIFICATION_REJECTED_COUNT))
    putAll(reviewFixCapExhaustionFields(row))
    putAll(auditGapFields(row))
    putAll(agentContextFields(row))
    put(MatKeys.REGENERATION_ACTIVATION_COUNT, row.intOrZero(MatKeys.REGENERATION_ACTIVATION_COUNT))
    put(MatKeys.REGENERATION_ATTEMPT_COUNT, row.intOrZero(MatKeys.REGENERATION_ATTEMPT_COUNT))
    put(MatKeys.REGENERATION_OUTCOME_COUNTS, parsePhaseOutcomes(row.stringOrEmpty("regeneration_outcome_counts_json")))
    put(MatKeys.CRASH_RECONCILIATION_COUNT, row.intOrZero(MatKeys.CRASH_RECONCILIATION_COUNT))
    put(
      "crash_reconciliation_reason_counts",
      parsePhaseOutcomes(row.stringOrEmpty("crash_reconciliation_reason_counts_json")),
    )
    put(MatKeys.LAST_INCOMPLETE_PHASE, row.stringOrEmpty(MatKeys.LAST_INCOMPLETE_PHASE))
    put(GoalTelemetryPayloadKeys.BLOCKED_REASON, row.stringOrEmpty(GoalTelemetryPayloadKeys.BLOCKED_REASON))
    put(LifeKeys.DURATION_SECONDS, durationSeconds(row, diagnostics))
    row.stringOrEmpty(LifeKeys.STALE_REASON).takeIf(String::isNotBlank)?.let {
      put(LifeKeys.STALE_REASON, it)
    }
    if (level == "full") {
      put(MatKeys.RESOLVED_BRANCH, row.stringOrEmpty(MatKeys.RESOLVED_BRANCH))
    }
  }

private fun reviewFixCapExhaustionFields(row: Map<String, Any?>): Map<String, Any?> {
  val availability = row.availability(LifeKeys.REVIEW_FIX_CAP_EXHAUSTED_AVAILABILITY)
  val exhausted =
    if (availability.measured) {
      row.intOrZero(LifeKeys.REVIEW_FIX_CAP_EXHAUSTED) == 1
    } else {
      null
    }
  return linkedMapOf(
    LifeKeys.REVIEW_FIX_CAP_EXHAUSTED_AVAILABILITY to availability.wireValue,
    LifeKeys.REVIEW_FIX_CAP_EXHAUSTED to exhausted,
  )
}

private fun auditGapFields(row: Map<String, Any?>): Map<String, Any?> {
  val availability = row.availability(LifeKeys.AUDIT_GAP_AVAILABILITY)
  val iterations =
    row.nullableInt(LifeKeys.AUDIT_GAP_ITERATION_COUNT)
      .takeIf { availability.measured }
  return linkedMapOf(
    LifeKeys.AUDIT_GAP_AVAILABILITY to availability.wireValue,
    LifeKeys.AUDIT_GAP_MEASUREMENT_GRAIN to AUDIT_GAP_MEASUREMENT_GRAIN_PER_RUN,
    LifeKeys.AUDIT_GAP_ITERATION_COUNT to iterations,
    LifeKeys.AUDIT_FIRST_PASS_CONVERGENCE to iterations?.let { it == 0 },
    LifeKeys.AUDIT_REPAIR_ITEM_AVAILABILITY to
      TelemetryMeasurementAvailability.UNAVAILABLE_UNSUPPORTED.wireValue,
  )
}

private fun agentContextFields(row: Map<String, Any?>): Map<String, Any?> {
  val agents = row.nameList(LifeKeys.RESOLVED_AGENT_IDS)
  val models = row.nameList(LifeKeys.LAUNCHED_MODELS)
  return linkedMapOf(
    LifeKeys.AGENT_CONTEXT_MEASUREMENT_GRAIN to AGENT_CONTEXT_MEASUREMENT_GRAIN_DISTINCT_PER_RUN,
    LifeKeys.RESOLVED_AGENT_AVAILABILITY to agents.availability().wireValue,
    LifeKeys.RESOLVED_AGENT_IDS to agents.values.takeIf { it.isNotEmpty() },
    LifeKeys.LAUNCHED_MODEL_AVAILABILITY to models.availability().wireValue,
    LifeKeys.LAUNCHED_MODELS to models.values.takeIf { it.isNotEmpty() },
  )
}

private data class ParsedNameList(val values: List<Any?>, val corrupt: Boolean)

private fun Map<String, Any?>.nameList(name: String): ParsedNameList {
  val raw = stringOrEmpty(name)
  if (raw.isBlank()) {
    return ParsedNameList(values = emptyList(), corrupt = false)
  }
  return try {
    ParsedNameList(values = JsonCodec.parseJsonArrayStrict(raw.trim()), corrupt = false)
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    ParsedNameList(values = emptyList(), corrupt = true)
  }
}

private fun ParsedNameList.availability(): TelemetryMeasurementAvailability =
  when {
    corrupt -> TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE
    values.isEmpty() -> TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE
    else -> TelemetryMeasurementAvailability.MEASURED
  }

private fun parsePhaseOutcomes(rawValue: String): Map<String, Any?> =
  JsonCodec.parseObjectOrNull(rawValue)
    ?.mapValues { (_, value) -> JsonCodec.jsonElementToValue(value) }
    .orEmpty()

private fun parseStoredJsonArray(
  rawValue: String,
  fieldName: String,
  diagnostics: RuntimeDiagnostics,
): List<Any?> {
  if (rawValue.isBlank()) {
    return emptyList()
  }
  return try {
    JsonCodec.parseJsonArrayStrict(rawValue.trim())
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    diagnostics.recordDegradedValue(
      seam = "telemetry.json_array.$fieldName",
      expected = "strict JSON array",
      used = rawValue.degradedValuePreview(),
      error = error,
    )
    emptyList()
  }
}

internal fun qualityCheckStartedPayload(row: Map<String, Any?>): Map<String, Any?> {
  val normalizedStack = normalizeStackLabel(row.stringOrEmpty(LifeKeys.DETECTED_STACK))
  val fallback = row.booleanFromInt(LifeKeys.FALLBACK) || normalizedStack.fallback
  return linkedMapOf<String, Any?>(
    LifeKeys.SESSION_ID to row.stringOrEmpty(LifeKeys.SESSION_ID),
    LifeKeys.ROUTED_SKILL to normalizeRoutedSkill(row.stringOrEmpty(LifeKeys.ROUTED_SKILL)),
    LifeKeys.DETECTED_STACK to normalizedStack.stack,
    LifeKeys.FALLBACK to fallback,
    LifeKeys.SCOPE_TYPE to row.stringOrEmpty(LifeKeys.SCOPE_TYPE),
    LifeKeys.INITIAL_FAILURE_COUNT to row.intOrZero(LifeKeys.INITIAL_FAILURE_COUNT),
    MatKeys.ORCHESTRATED to false,
  ).apply {
    val fallbackReason =
      row.stringOrEmpty(LifeKeys.FALLBACK_REASON).ifBlank {
        normalizedStack.fallbackReason.orEmpty()
      }
    if (fallback && fallbackReason.isNotBlank()) {
      put(LifeKeys.FALLBACK_REASON, fallbackReason)
    }
  }
}

internal fun qualityCheckFinishedPayload(
  row: Map<String, Any?>,
  level: String,
  diagnostics: RuntimeDiagnostics,
): Map<String, Any?> {
  val result = row.stringOrEmpty(LifeKeys.RESULT).ifBlank { "skipped" }
  val reconcilerStale = result == STALE_TERMINAL_VALUE
  val finalFailureCount =
    row.nullableInt(LifeKeys.FINAL_FAILURE_COUNT)
      .takeUnless { reconcilerStale }
  return qualityCheckStartedPayload(row).toMutableMap().apply {
    put(LifeKeys.FINAL_FAILURE_COUNT, finalFailureCount)
    put(
      LifeKeys.FINAL_FAILURE_COUNT_AVAILABILITY,
      when {
        reconcilerStale -> TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE
        finalFailureCount == null -> TelemetryMeasurementAvailability.UNKNOWN
        else -> TelemetryMeasurementAvailability.MEASURED
      }.wireValue,
    )
    put(
      LifeKeys.COMPLETION,
      when (reconcilerStale) {
        true -> LifecycleSessionCompletion.RECONCILER_STALE
        false -> LifecycleSessionCompletion.OPERATOR_COMPLETED
      }.wireValue,
    )
    put(LifeKeys.ITERATIONS, row.intOrZero(LifeKeys.ITERATIONS))
    put(LifeKeys.RESULT, result)
    put(LifeKeys.DURATION_SECONDS, durationSeconds(row, diagnostics))
    row.stringOrEmpty(LifeKeys.STALE_REASON).takeIf(String::isNotBlank)?.let {
      put(LifeKeys.STALE_REASON, it)
    }
    if (level == "full") {
      put(
        LifeKeys.FAILING_CHECK_NAMES,
        parseStoredJsonArray(
          row.stringOrEmpty(LifeKeys.FAILING_CHECK_NAMES),
          LifeKeys.FAILING_CHECK_NAMES,
          diagnostics,
        ),
      )
      put(
        LifeKeys.UNSUPPORTED_REASON,
        row.stringOrEmpty(LifeKeys.UNSUPPORTED_REASON),
      )
    }
  }
}

internal const val STALE_TERMINAL_VALUE: String = "stale"

internal fun featureVerifyStartedPayload(
  row: Map<String, Any?>,
  level: String,
): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    LifeKeys.SESSION_ID to row.stringOrEmpty(LifeKeys.SESSION_ID),
    LifeKeys.ACCEPTANCE_CRITERIA_COUNT to row.intOrZero(LifeKeys.ACCEPTANCE_CRITERIA_COUNT),
    LifeKeys.ROLLOUT_RELEVANT to row.booleanFromInt(LifeKeys.ROLLOUT_RELEVANT),
    MatKeys.ORCHESTRATED to false,
  ).apply {
    if (level == "full") {
      put(LifeKeys.SPEC_SUMMARY, row.stringOrEmpty(LifeKeys.SPEC_SUMMARY))
    }
  }

internal fun featureVerifyFinishedPayload(
  row: Map<String, Any?>,
  level: String,
  diagnostics: RuntimeDiagnostics,
): Map<String, Any?> =
  featureVerifyStartedPayload(row, level).toMutableMap().apply {
    put(LifeKeys.FEATURE_FLAG_AUDIT_PERFORMED, row.booleanFromInt(LifeKeys.FEATURE_FLAG_AUDIT_PERFORMED))
    put(LifeKeys.REVIEW_ITERATIONS, row.intOrZero(LifeKeys.REVIEW_ITERATIONS))
    put(LifeKeys.AUDIT_RESULT, row.stringOrEmpty(LifeKeys.AUDIT_RESULT).ifBlank { "skipped" })
    put(LifeKeys.COMPLETION_STATUS, row.stringOrEmpty(LifeKeys.COMPLETION_STATUS))
    put(LifeKeys.HISTORY_RELEVANCE, row.stringOrEmpty(LifeKeys.HISTORY_RELEVANCE).ifBlank { "none" })
    put(LifeKeys.HISTORY_HELPFULNESS, row.stringOrEmpty(LifeKeys.HISTORY_HELPFULNESS).ifBlank { "none" })
    val durationAvailability = verifyDurationAvailability(row)
    put(LifeKeys.DURATION_SECONDS, durationSeconds(row, diagnostics).takeIf { durationAvailability.measured })
    put(LifeKeys.DURATION_SECONDS_AVAILABILITY, durationAvailability.wireValue)
    if (level == "full") {
      put(
        LifeKeys.GAPS_FOUND,
        parseStoredJsonArray(
          row.stringOrEmpty(LifeKeys.GAPS_FOUND),
          LifeKeys.GAPS_FOUND,
          diagnostics,
        ),
      )
    }
  }

private fun verifyDurationAvailability(row: Map<String, Any?>): TelemetryMeasurementAvailability =
  when {
    row.stringOrEmpty(LifeKeys.COMPLETION_STATUS) == STALE_TERMINAL_VALUE ->
      TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE
    row.stringOrEmpty(GoalTelemetryPayloadKeys.STARTED_AT).isBlank() ||
      row.stringOrEmpty(GoalTelemetryPayloadKeys.FINISHED_AT).isBlank() ->
      TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE
    else -> TelemetryMeasurementAvailability.MEASURED
  }

internal fun prDescriptionPayload(
  record: PrDescriptionGeneratedRecord,
  level: String,
): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    LifeKeys.SESSION_ID to record.sessionId,
    LifeKeys.COMMIT_COUNT to record.commitCount,
    LifeKeys.FILES_CHANGED_COUNT to record.filesChangedCount,
    LifeKeys.WAS_EDITED_BY_USER to record.wasEditedByUser,
    LifeKeys.PR_CREATED to record.prCreated,
  ).apply {
    if (level == "full") {
      put(LifeKeys.PR_TITLE, record.prTitle)
    }
  }
