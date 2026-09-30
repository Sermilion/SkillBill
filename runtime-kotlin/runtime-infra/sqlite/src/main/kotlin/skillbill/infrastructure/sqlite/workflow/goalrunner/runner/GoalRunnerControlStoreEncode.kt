package skillbill.infrastructure.sqlite.workflow.goalrunner.runner

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowTimestampPayloadKeys
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.parseExecutionLeaseInstant
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import java.time.Instant

internal fun GoalRunnerReviewPolicy.toArtifactMap(): Map<String, Any?> =
  buildMap {
    put(FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE, codeReviewMode.wireValue)
    if (agentAddonSelection.entries.isNotEmpty()) {
      put(
        FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION,
        agentAddonSelection.entries.map { entry ->
          mapOf(
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG to entry.slug,
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY to entry.sourceIdentity,
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256 to entry.contentSha256,
          )
        },
      )
    }
  }

internal fun GoalRunnerOutOfBandAcceptance.toArtifactMap(): Map<String, Any?> =
  mapOf(
    SharedPayloadKeys.SUBTASK_ID to subtaskId,
    DecompositionManifestPayloadKeys.COMMIT_SHA to commitSha,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTANCE_REASON to reason,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTED_AT to acceptedAt,
  )

internal fun GoalRunnerExecutionLease.toArtifactMap(source: Map<*, *>? = null): Map<String, Any?> =
  mapOf(
    "generation" to generation,
    "owner_token" to ownerToken,
    "host_identity" to hostIdentity,
    "boot_identity" to bootIdentity,
    "pid" to pid,
    "process_birth_token" to processBirthToken,
    WorkflowTimestampPayloadKeys.HEARTBEAT_AT to
      leaseTimestamp(heartbeatAt, source, WorkflowTimestampPayloadKeys.HEARTBEAT_AT),
    WorkflowTimestampPayloadKeys.EXPIRES_AT to
      leaseTimestamp(expiresAt, source, WorkflowTimestampPayloadKeys.EXPIRES_AT),
  )

internal fun GoalRunnerControlState.toArtifactMap(source: Map<String, Any?>? = null): Map<String, Any?> =
  mapOf(
    "stop_after_subtask_id" to stopAfterSubtaskId,
    "pause_requested" to pauseRequested,
    "pause_consumed" to pauseConsumed,
    "paused" to paused,
    "pause_reason" to pauseReason,
    "paused_at" to pausedAt,
    "stop_after_consumed" to stopAfterConsumed,
    "repository_identity" to repositoryIdentity,
    "execution_lease" to executionLease?.toArtifactMap(source?.get("execution_lease") as? Map<*, *>),
    "active_duration_ms" to activeDurationMs,
    "active_duration_as_of" to activeDurationAsOf,
    "current_subtask_id" to currentSubtaskId,
    "subtask_active_duration_ms" to subtaskActiveDurationMs,
    "subtask_active_duration_as_of" to subtaskActiveDurationAsOf,
    "validation_quality_retries_by_subtask" to
      validationQualityRetriesBySubtask.entries.associate { (k, v) -> k.toString() to v },
    "pending_re_attempt_cause_by_subtask" to
      pendingReAttemptCauseBySubtask.entries.associate { (k, v) -> k.toString() to v },
    "pending_causing_loop_entry_by_subtask" to
      pendingCausingLoopEntryBySubtask.entries.associate { (k, v) -> k.toString() to v },
  )

private fun leaseTimestamp(
  value: Instant,
  source: Map<*, *>?,
  field: String,
): String {
  val original = source?.get(field) as? String ?: return value.toString()
  return if (parseExecutionLeaseInstant(field, original) == value) original else value.toString()
}
