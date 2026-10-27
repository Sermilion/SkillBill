package skillbill.application.review.stats

import skillbill.contracts.SharedPayloadKeys
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.GoalAssessmentStats
import skillbill.review.model.GoalBlockedSubtaskSummary
import skillbill.review.model.GoalInteractionScenarioStats
import skillbill.review.model.GoalModeStats
import skillbill.review.model.GoalRunSummary
import skillbill.review.model.GoalTestProbeStats
import skillbill.review.model.GoalWorkflowStats

internal fun FeatureTaskRuntimeWorkflowStats.toPayload(): Map<String, Any?> =
  linkedMapOf(
    "workflow" to "feature-task-runtime",
    "total_runs" to totalRuns,
    "finished_runs" to finishedRuns,
    "observed_runs" to observedRuns,
    "reconciler_closed_runs" to reconcilerClosedRuns,
    "in_progress_runs" to inProgressRuns,
    "feature_size_counts" to featureSizeCounts,
    "completion_status_counts" to completionStatusCounts,
    "phase_outcome_counts" to phaseOutcomeCounts,
    "completed_runs" to completedRuns,
    "completed_rate" to completedRate,
    "blocked_runs" to blockedRuns,
    "blocked_rate" to blockedRate,
    "decomposed_runs" to decomposedRuns,
    "decomposed_rate" to decomposedRate,
    "error_runs" to errorRuns,
    "error_rate" to errorRate,
    "average_completed_phase_count" to averageCompletedPhaseCount,
    "estimated_token_runs_with_value" to estimatedTokenRunsWithValue,
    "average_estimated_total_tokens" to averageEstimatedTotalTokens,
  )

internal fun GoalWorkflowStats.toPayload(): Map<String, Any?> =
  linkedMapOf(
    "workflow" to "bill-goal-run",
    "total_runs" to totalRuns,
    "logical_goals" to logicalGoals,
    "invocations_with_unknown_goal" to invocationsWithUnknownGoal,
    "goal_identity_availability" to goalIdentityAvailability,
    "resumed_invocations" to resumedInvocations,
    "finished_runs" to finishedRuns,
    "in_progress_runs" to inProgressRuns,
    "completion_status_counts" to completionStatusCounts,
    "completed_runs" to completedRuns,
    "completed_rate" to completedRate,
    "blocked_runs" to blockedRuns,
    "blocked_rate" to blockedRate,
    "subtask_outcome_counts" to subtaskOutcomeCounts,
    "total_subtask_events" to totalSubtaskEvents,
    "average_run_duration_ms" to averageRunDurationMs,
    "average_subtask_duration_ms" to averageSubtaskDurationMs,
    "average_attempt_count" to averageAttemptCount,
    "top_blocked_subtasks" to topBlockedSubtasks.map(GoalBlockedSubtaskSummary::toPayload),
    "most_recent_run" to mostRecentRun?.toPayload(),
    "by_mode" to byMode.mapValues { (_, v) -> v.toPayload() },
    GoalStatsPayloadKeys.INTERACTION_SCENARIOS to interactionScenarios.toPayload(),
    GoalStatsPayloadKeys.TEST_PROBES to testProbes.toPayload(),
    GoalStatsPayloadKeys.GOAL_ASSESSMENT to goalAssessment.toPayload(),
  )

internal fun GoalInteractionScenarioStats.toPayload(): Map<String, Any?> =
  linkedMapOf(
    GoalStatsPayloadKeys.TRACED to traced,
    GoalStatsPayloadKeys.BROKEN to broken,
    GoalStatsPayloadKeys.REPAIRED to repaired,
    GoalStatsPayloadKeys.UNKNOWN to unknown,
  )

internal fun GoalTestProbeStats.toPayload(): Map<String, Any?> =
  linkedMapOf(
    GoalStatsPayloadKeys.KILLED to killed,
    GoalStatsPayloadKeys.SURVIVED to survived,
    GoalStatsPayloadKeys.REPAIRED to repaired,
    GoalStatsPayloadKeys.NOT_RUN to notRun,
  )

internal fun GoalAssessmentStats.toPayload(): Map<String, Any?> =
  linkedMapOf(
    GoalStatsPayloadKeys.FINDINGS_BY_SEVERITY to
      linkedMapOf(
        GoalStatsPayloadKeys.BLOCKER to blockerFindings,
        GoalStatsPayloadKeys.MAJOR to majorFindings,
        GoalStatsPayloadKeys.MINOR to minorFindings,
      ),
    GoalStatsPayloadKeys.CONFIRMED to confirmed,
    GoalStatsPayloadKeys.REFUTED to refuted,
    GoalStatsPayloadKeys.ROUNDS to rounds,
    GoalStatsPayloadKeys.OPEN_FINDINGS to openFindings,
    GoalStatsPayloadKeys.EXIT_REASONS to exitReasonCounts,
  )

internal fun GoalModeStats.toPayload(): Map<String, Any?> =
  linkedMapOf(
    "total_runs" to totalRuns,
    "finished_runs" to finishedRuns,
    "in_progress_runs" to inProgressRuns,
    "completed_runs" to completedRuns,
    "completed_rate" to completedRate,
    "blocked_runs" to blockedRuns,
    "blocked_rate" to blockedRate,
    "average_run_duration_ms" to averageRunDurationMs,
  )

internal fun GoalRunSummary.toPayload(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "feature_name" to featureName,
    SharedPayloadKeys.STATUS to status,
    "started_at" to startedAt,
    "finished_at" to finishedAt,
    "duration_ms" to durationMs,
    "resumed" to resumed,
    "subtask_total" to subtaskTotal,
  )

internal fun GoalBlockedSubtaskSummary.toPayload(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.SUBTASK_ID to subtaskId,
    "subtask_name" to subtaskName,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "blocked_reason" to blockedReason,
    "attempt_count" to attemptCount,
  )

internal fun FeatureVerifyWorkflowStats.toPayload(): Map<String, Any?> =
  linkedMapOf(
    "workflow" to "bill-feature-verify",
    "total_runs" to totalRuns,
    "finished_runs" to finishedRuns,
    "in_progress_runs" to inProgressRuns,
    "completion_status_counts" to completionStatusCounts,
    "audit_result_counts" to auditResultCounts,
    "rollout_relevant_runs" to rolloutRelevantRuns,
    "rollout_relevant_rate" to rolloutRelevantRate,
    "feature_flag_audit_performed_runs" to featureFlagAuditPerformedRuns,
    "feature_flag_audit_performed_rate" to featureFlagAuditPerformedRate,
    "history_read_runs" to historyReadRuns,
    "history_read_rate" to historyReadRate,
    "history_relevant_runs" to historyRelevantRuns,
    "history_relevant_rate" to historyRelevantRate,
    "history_helpful_runs" to historyHelpfulRuns,
    "history_helpful_rate" to historyHelpfulRate,
    "history_relevance_counts" to historyRelevanceCounts,
    "history_helpfulness_counts" to historyHelpfulnessCounts,
    "runs_with_gaps_found" to runsWithGapsFound,
    "average_acceptance_criteria_count" to averageAcceptanceCriteriaCount,
    "average_review_iterations" to averageReviewIterations,
    "average_duration_seconds" to averageDurationSeconds,
  )
