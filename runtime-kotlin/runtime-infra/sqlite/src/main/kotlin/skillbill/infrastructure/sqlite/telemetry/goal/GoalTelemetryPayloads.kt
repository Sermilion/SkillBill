package skillbill.infrastructure.sqlite.telemetry.goal

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.sqlite.core.ops.degradedValuePreview
import skillbill.infrastructure.sqlite.core.ops.recordDegradedValue
import skillbill.infrastructure.sqlite.telemetry.lifecycle.booleanFromInt
import skillbill.infrastructure.sqlite.telemetry.lifecycle.intOrZero
import skillbill.infrastructure.sqlite.telemetry.lifecycle.longOrZero
import skillbill.infrastructure.sqlite.telemetry.lifecycle.stringOrEmpty
import skillbill.infrastructure.sqlite.telemetry.redaction.redactIssueKey
import skillbill.infrastructure.sqlite.telemetry.redaction.redactIssueKeyReferences
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

private const val MILLIS_PER_SECOND = 1000L

private fun parseAgentIdArray(
  rawValue: String,
  workflowId: String,
  diagnostics: RuntimeDiagnostics,
): List<Any?> {
  if (rawValue.isBlank()) return emptyList()
  val trimmed = rawValue.trim()
  if (!trimmed.startsWith("[")) {
    diagnostics.recordDegradedValue(
      seam = "telemetry.participating_agent_ids",
      expected = "JSON array",
      used = rawValue.degradedValuePreview(),
    )
    return emptyList()
  }
  return try {
    JsonCodec.parseJsonArrayStrict(trimmed)
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    diagnostics.recordDegradedValue(
      seam = "telemetry.participating_agent_ids",
      expected = "strict JSON array for workflow $workflowId",
      used = rawValue.degradedValuePreview(),
      error = error,
    )
    emptyList()
  }
}

private fun Map<String, Any?>.redactedWorkflowId(
  column: String,
  level: String,
  salt: String,
): String = redactIssueKeyReferences(stringOrEmpty(column), stringOrEmpty(SharedPayloadKeys.ISSUE_KEY), level, salt)

internal fun goalStartedPayload(
  row: Map<String, Any?>,
  level: String,
  salt: String,
): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    SharedPayloadKeys.WORKFLOW_ID to row.redactedWorkflowId("workflow_id", level, salt),
    SharedPayloadKeys.ISSUE_KEY to redactIssueKey(row.stringOrEmpty(SharedPayloadKeys.ISSUE_KEY), level, salt),
    GoalTelemetryPayloadKeys.SUBTASK_TOTAL to row.intOrZero(GoalTelemetryPayloadKeys.SUBTASK_TOTAL),
    GoalTelemetryPayloadKeys.RESUMED to row.booleanFromInt(GoalTelemetryPayloadKeys.RESUMED),
    GoalTelemetryPayloadKeys.STARTED_AT to row.stringOrEmpty(GoalTelemetryPayloadKeys.STARTED_AT),
    SharedPayloadKeys.STATUS to "running",
    LifecycleTelemetryPayloadKeys.MODE to row.stringOrEmpty(LifecycleTelemetryPayloadKeys.MODE).ifBlank { "runtime" },
  ).apply {
    if (level == "full") {
      put(GoalTelemetryPayloadKeys.FEATURE_NAME, row.stringOrEmpty(GoalTelemetryPayloadKeys.FEATURE_NAME))
    }
  }

internal fun goalFinishedPayload(
  row: Map<String, Any?>,
  level: String,
  salt: String,
): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    SharedPayloadKeys.WORKFLOW_ID to row.redactedWorkflowId("workflow_id", level, salt),
    SharedPayloadKeys.ISSUE_KEY to redactIssueKey(row.stringOrEmpty(SharedPayloadKeys.ISSUE_KEY), level, salt),
    SharedPayloadKeys.STATUS to row.stringOrEmpty(SharedPayloadKeys.STATUS),
    GoalTelemetryPayloadKeys.STARTED_AT to row.stringOrEmpty(GoalTelemetryPayloadKeys.STARTED_AT),
    GoalTelemetryPayloadKeys.FINISHED_AT to row.stringOrEmpty(GoalTelemetryPayloadKeys.FINISHED_AT),
    LifecycleTelemetryPayloadKeys.DURATION_SECONDS to secondsFromMillis(row.longOrZero("finished_duration_ms")),
    GoalTelemetryPayloadKeys.SUBTASKS_COMPLETE to row.intOrZero(GoalTelemetryPayloadKeys.SUBTASKS_COMPLETE),
    GoalTelemetryPayloadKeys.SUBTASKS_BLOCKED to row.intOrZero(GoalTelemetryPayloadKeys.SUBTASKS_BLOCKED),
    GoalTelemetryPayloadKeys.SUBTASKS_SKIPPED to row.intOrZero(GoalTelemetryPayloadKeys.SUBTASKS_SKIPPED),
    LifecycleTelemetryPayloadKeys.MODE to row.stringOrEmpty(LifecycleTelemetryPayloadKeys.MODE).ifBlank { "runtime" },
    GoalTelemetryPayloadKeys.STOP_REASON to row[GoalTelemetryPayloadKeys.STOP_REASON]?.toString(),
  )

internal fun goalIssueFinishedPayload(
  row: Map<String, Any?>,
  level: String,
  salt: String,
  diagnostics: RuntimeDiagnostics,
): Map<String, Any?> {
  val firstStartedAt = row.stringOrEmpty(GoalTelemetryPayloadKeys.FIRST_STARTED_AT)
  val finishedAt = row.stringOrEmpty(GoalTelemetryPayloadKeys.FINISHED_AT)
  return linkedMapOf<String, Any?>(
    GoalTelemetryPayloadKeys.PARENT_WORKFLOW_ID to row.redactedWorkflowId("parent_workflow_id", level, salt),
    SharedPayloadKeys.ISSUE_KEY to redactIssueKey(row.stringOrEmpty(SharedPayloadKeys.ISSUE_KEY), level, salt),
    SharedPayloadKeys.STATUS to row.stringOrEmpty(SharedPayloadKeys.STATUS),
    GoalTelemetryPayloadKeys.SUBTASKS_COMPLETE to row.intOrZero(GoalTelemetryPayloadKeys.SUBTASKS_COMPLETE),
    GoalTelemetryPayloadKeys.SUBTASKS_BLOCKED to row.intOrZero(GoalTelemetryPayloadKeys.SUBTASKS_BLOCKED),
    GoalTelemetryPayloadKeys.SUBTASKS_SKIPPED to row.intOrZero(GoalTelemetryPayloadKeys.SUBTASKS_SKIPPED),
    GoalTelemetryPayloadKeys.TOTAL_INVOCATIONS to row.intOrZero(GoalTelemetryPayloadKeys.TOTAL_INVOCATIONS),
    GoalTelemetryPayloadKeys.TOTAL_BLOCKS to row.intOrZero(GoalTelemetryPayloadKeys.TOTAL_BLOCKS),
    GoalTelemetryPayloadKeys.TOTAL_RESUMES to row.intOrZero(GoalTelemetryPayloadKeys.TOTAL_RESUMES),
    GoalTelemetryPayloadKeys.FIRST_STARTED_AT to firstStartedAt,
    GoalTelemetryPayloadKeys.FINISHED_AT to finishedAt,
    LifecycleTelemetryPayloadKeys.DURATION_SECONDS to durationBetweenSeconds(firstStartedAt, finishedAt, diagnostics),
    LifecycleTelemetryPayloadKeys.MODE to row.stringOrEmpty(LifecycleTelemetryPayloadKeys.MODE),
  )
}

internal fun goalSubtaskFinishedPayload(
  row: Map<String, Any?>,
  level: String,
  salt: String,
  diagnostics: RuntimeDiagnostics,
): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    SharedPayloadKeys.WORKFLOW_ID to row.redactedWorkflowId("workflow_id", level, salt),
    SharedPayloadKeys.ISSUE_KEY to redactIssueKey(row.stringOrEmpty(SharedPayloadKeys.ISSUE_KEY), level, salt),
    SharedPayloadKeys.SUBTASK_ID to row.intOrZero(SharedPayloadKeys.SUBTASK_ID),
    SharedPayloadKeys.STATUS to row.stringOrEmpty(SharedPayloadKeys.STATUS),
    GoalTelemetryPayloadKeys.STARTED_AT to row.stringOrEmpty(GoalTelemetryPayloadKeys.STARTED_AT),
    GoalTelemetryPayloadKeys.FINISHED_AT to row.stringOrEmpty(GoalTelemetryPayloadKeys.FINISHED_AT),
    LifecycleTelemetryPayloadKeys.DURATION_SECONDS to secondsFromMillis(row.longOrZero("duration_ms")),
    GoalTelemetryPayloadKeys.ATTEMPT_COUNT to row.intOrZero(GoalTelemetryPayloadKeys.ATTEMPT_COUNT),
    GoalTelemetryPayloadKeys.BLOCKED_REASON to row[GoalTelemetryPayloadKeys.BLOCKED_REASON]?.toString(),
  ).apply {
    if (level == "full") {
      put(GoalTelemetryPayloadKeys.SUBTASK_NAME, row.stringOrEmpty(GoalTelemetryPayloadKeys.SUBTASK_NAME))
      put(
        GoalTelemetryPayloadKeys.FINALIZING_AGENT_ID,
        row[GoalTelemetryPayloadKeys.FINALIZING_AGENT_ID]?.toString()?.takeIf(String::isNotBlank),
      )
      put(
        GoalTelemetryPayloadKeys.PARTICIPATING_AGENT_IDS,
        parseAgentIdArray(
          row.stringOrEmpty(GoalTelemetryPayloadKeys.PARTICIPATING_AGENT_IDS),
          row.stringOrEmpty(SharedPayloadKeys.WORKFLOW_ID),
          diagnostics,
        ),
      )
      put(
        GoalTelemetryPayloadKeys.BOUNDARY_HISTORY_WRITTEN,
        row.booleanFromInt(GoalTelemetryPayloadKeys.BOUNDARY_HISTORY_WRITTEN),
      )
      put(
        GoalTelemetryPayloadKeys.BOUNDARY_HISTORY_VALUE,
        row.stringOrEmpty(
          GoalTelemetryPayloadKeys.BOUNDARY_HISTORY_VALUE,
        ).ifBlank {
          "none"
        },
      )
    }
  }

private fun secondsFromMillis(durationMs: Long): Long = durationMs.coerceAtLeast(0) / MILLIS_PER_SECOND

private fun durationBetweenSeconds(
  startedAt: String,
  finishedAt: String,
  diagnostics: RuntimeDiagnostics,
): Long {
  val start = parseTelemetryTimestamp(startedAt, diagnostics, "first_started_at") ?: return 0
  val end = parseTelemetryTimestamp(finishedAt, diagnostics, "finished_at") ?: return 0
  return Duration.between(start, end).seconds.coerceAtLeast(0)
}

private fun parseTelemetryTimestamp(
  value: String,
  diagnostics: RuntimeDiagnostics,
  fieldName: String,
): Instant? =
  runCatching {
    Instant.parse(value)
  }.recoverCatching {
    LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
  }.getOrElse { error ->
    diagnostics.recordDegradedValue(
      seam = "telemetry.goal_issue.$fieldName",
      expected = "RFC 3339 instant or local date-time",
      used = value.degradedValuePreview(),
      error = error,
    )
    null
  }
