package skillbill.infrastructure.sqlite.workflow.goalrunner.runner

import skillbill.contracts.JsonCodec
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.time.parsePersistedInstantOrNull
import skillbill.contracts.goalrunner.GoalRunnerControlPayloadKeys as SharedGoalRunnerControlPayloadKeys

internal fun decodeControlState(raw: String): GoalRunnerControlState {
  val state =
    JsonCodec.parseObjectOrNull(raw)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?: goalRunnerControlSchemaError("durable record must be an object.")
  state.keys.forEach { key ->
    if (key !in allowedControlKeys) {
      goalRunnerControlSchemaError("has unsupported field '$key'.")
    }
  }
  return GoalRunnerControlState(
    stopAfterSubtaskId =
      state[SharedGoalRunnerControlPayloadKeys.STOP_AFTER_SUBTASK_ID]
        .toPositiveIntOrNull(SharedGoalRunnerControlPayloadKeys.STOP_AFTER_SUBTASK_ID),
    pauseRequested = state.booleanOrDefault("pause_requested", false),
    pauseConsumed = state.booleanOrDefault("pause_consumed", false),
    paused = state.booleanOrDefault("paused", false),
    pauseReason = state.nullableString("pause_reason"),
    goalBlockedReason = state.nullableString(GoalRunnerControlPayloadKeys.BLOCKED_REASON),
    pausedAt = state.nullableString("paused_at") ?: legacyPausedAt(state),
    stopAfterConsumed = state.booleanOrDefault("stop_after_consumed", false),
    repositoryIdentity = state.nullableString("repository_identity"),
    planningSourceRoot = state.nullableString(SharedGoalRunnerControlPayloadKeys.PLANNING_SOURCE_ROOT),
    executionLease = state["execution_lease"]?.let(::decodeExecutionLease),
    activeDurationMs = state.nonNegativeLongOrDefault("active_duration_ms"),
    activeDurationAsOf = state.nullableString("active_duration_as_of"),
    currentSubtaskId = state["current_subtask_id"].toPositiveIntOrNull("current_subtask_id"),
    subtaskActiveDurationMs = state.nonNegativeLongOrDefault("subtask_active_duration_ms"),
    subtaskActiveDurationAsOf = state.nullableString("subtask_active_duration_as_of"),
    validationQualityRetriesBySubtask =
      state.intIntMap(
        SharedGoalRunnerControlPayloadKeys.VALIDATION_QUALITY_RETRIES_BY_SUBTASK,
      ),
    pendingReAttemptCauseBySubtask = state.intStringMap("pending_re_attempt_cause_by_subtask"),
    pendingCausingLoopEntryBySubtask = state.intStringMap("pending_causing_loop_entry_by_subtask"),
    goalCompletedAt =
      state.nullableString("goal_completed_at")?.also { value ->
        if (parsePersistedInstantOrNull(value) == null) {
          goalRunnerControlSchemaError("field 'goal_completed_at' must be a supported persisted instant.")
        }
      },
    goalPullRequestUrl = state.nullableString("goal_pull_request_url"),
    qaSelection =
      state.nullableString(GoalRunnerControlPayloadKeys.QA_SELECTION)?.let(FeatureTaskRuntimeQaSelection::fromWire),
    executionWorktreePath = state.nullableString(GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_PATH),
    executionWorktreeRemovedAt =
      state.nullableString(GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_REMOVED_AT)?.also { value ->
        if (parsePersistedInstantOrNull(value) == null) {
          goalRunnerControlSchemaError(
            "field '${GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_REMOVED_AT}' must be a supported " +
              "persisted instant.",
          )
        }
      },
    executionWorktreeRetentionReason =
      state.nullableString(GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_RETENTION_REASON),
    executionWorktreeRetentionDetail =
      state.nullableString(GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_RETENTION_DETAIL),
    executionMainCheckoutOutcome =
      state.nullableString(GoalRunnerControlPayloadKeys.EXECUTION_MAIN_CHECKOUT_OUTCOME),
    executionMainCheckoutDetail =
      state.nullableString(GoalRunnerControlPayloadKeys.EXECUTION_MAIN_CHECKOUT_DETAIL),
    launchWarning = state.nullableString(GoalRunnerControlPayloadKeys.LAUNCH_WARNING),
    archiveImportFingerprint = state.nullableString(GoalRunnerControlPayloadKeys.ARCHIVE_IMPORT_FINGERPRINT),
    archiveImportSpecsPending =
      state.booleanOrDefault(GoalRunnerControlPayloadKeys.ARCHIVE_IMPORT_SPECS_PENDING, false),
  )
}

private val allowedControlKeys =
  setOf(
    SharedGoalRunnerControlPayloadKeys.STOP_AFTER_SUBTASK_ID,
    "pause_requested",
    "pause_consumed",
    "paused",
    "pause_reason",
    "paused_at",
    "stop_after_consumed",
    "repository_identity",
    SharedGoalRunnerControlPayloadKeys.PLANNING_SOURCE_ROOT,
    "execution_lease",
    "active_duration_ms",
    "active_duration_as_of",
    "current_subtask_id",
    "subtask_active_duration_ms",
    "subtask_active_duration_as_of",
    SharedGoalRunnerControlPayloadKeys.VALIDATION_QUALITY_RETRIES_BY_SUBTASK,
    "pending_re_attempt_cause_by_subtask",
    "pending_causing_loop_entry_by_subtask",
    "goal_completed_at",
    "goal_pull_request_url",
    GoalRunnerControlPayloadKeys.BLOCKED_REASON,
    GoalRunnerControlPayloadKeys.QA_SELECTION,
    GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_PATH,
    GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_REMOVED_AT,
    GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_RETENTION_REASON,
    GoalRunnerControlPayloadKeys.EXECUTION_WORKTREE_RETENTION_DETAIL,
    GoalRunnerControlPayloadKeys.EXECUTION_MAIN_CHECKOUT_OUTCOME,
    GoalRunnerControlPayloadKeys.EXECUTION_MAIN_CHECKOUT_DETAIL,
    GoalRunnerControlPayloadKeys.LAUNCH_WARNING,
    GoalRunnerControlPayloadKeys.ARCHIVE_IMPORT_FINGERPRINT,
    GoalRunnerControlPayloadKeys.ARCHIVE_IMPORT_SPECS_PENDING,
  )
