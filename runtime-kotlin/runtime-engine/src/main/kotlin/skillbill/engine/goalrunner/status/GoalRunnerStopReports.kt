package skillbill.engine.goalrunner.status

import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.goalrunner.execution.core.StoppedReportArgs
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.goalrunner.model.GoalPullRequestStatus
import skillbill.goalrunner.model.GoalRunnerContinuationMode
import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerProcessState
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStopReport
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun stopped(args: StoppedReportArgs): GoalRunnerRunReport.Stopped =
  GoalRunnerRunReport.Stopped(
    issueKey = args.issueKey,
    attemptedSubtasks = args.attempted,
    stop =
      GoalRunnerStopReport(
        issueKey = args.issueKey,
        subtaskId = args.subtaskId,
        reason = args.reason,
        blockedReason = args.blockedReason,
        workflowId = args.workflowId,
        lastResumableStep = args.lastResumableStep,
      ),
  )

fun completed(
  manifest: DecompositionManifest,
  attempted: List<Int>,
  pullRequestUrl: String?,
  pullRequestStatus: GoalPullRequestStatus,
  ledger: UnaddressedFindingsLedger?,
): GoalRunnerRunReport.Completed {
  return GoalRunnerRunReport.Completed(
    issueKey = manifest.issueKey,
    attemptedSubtasks = attempted,
    featureName = manifest.featureName,
    pullRequestUrl = pullRequestUrl,
    pullRequestStatus = pullRequestStatus,
    subtasksCompleted = manifest.subtasks.count { it.status.decompositionStatus() == DecompositionStatus.COMPLETE },
    subtasksPending =
      manifest.subtasks.count {
        it.status.decompositionStatus() !in
          setOf(
            DecompositionStatus.COMPLETE,
            DecompositionStatus.SKIPPED,
            DecompositionStatus.BLOCKED,
          )
      },
    subtasksBlocked = manifest.subtasks.count { it.status.decompositionStatus() == DecompositionStatus.BLOCKED },
    unaddressedFindingCount = ledger?.findings?.size,
    unaddressedSeverityBreakdown = ledger?.severityBreakdown.orEmpty(),
  )
}

fun unknownGoal(issueKey: String): GoalRunnerRunReport.Stopped =
  stopped(
    StoppedReportArgs(
      issueKey = issueKey,
      attempted = emptyList(),
      subtaskId = 0,
      reason = GoalRunnerStopReason.BLOCKED,
      blockedReason = "No decomposed parent workflow was found for $issueKey.",
      workflowId = null,
      lastResumableStep = "preplan",
    ),
  )

fun String.withStopDiagnostics(
  knownWorkflowId: String?,
  progress: GoalRunnerWorkflowProgress?,
  liveness: GoalRunnerLivenessSnapshot?,
): String {
  val details =
    listOfNotNull(
      knownWorkflowId?.let { workflowId -> "workflow_id=$workflowId" },
      progress?.currentStepId?.takeIf(String::isNotBlank)?.let { step -> "current_step=$step" },
      progress?.latestLivenessSignal?.takeIf(String::isNotBlank)?.let { signal -> "latest_liveness=$signal" },
      progress?.lastSnapshotUpdatedAt?.takeIf(String::isNotBlank)?.let { at -> "last_snapshot_at=$at" },
      liveness?.lastFileActivityAt?.takeIf(String::isNotBlank)?.let { at -> "last_file_activity_at=$at" },
      liveness?.lastOutputAt?.takeIf(String::isNotBlank)?.let { at -> "last_output_at=$at" },
    ).joinToString(", ")
  return if (details.isBlank()) this else "$this [$details]"
}

fun GoalRunnerReconciledOutcome.Stop.isRecoverableValidationBlock(
  phaseQuery: FeatureTaskRuntimePhaseQuery? = null,
): Boolean {
  if (reason !in setOf(GoalRunnerStopReason.BLOCKED, GoalRunnerStopReason.FAILED)) {
    return false
  }
  if (lastResumableStep != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) return false
  val workflowId = workflowId
  if (workflowId != null && phaseQuery != null) {
    val disposition =
      phaseQuery.loadPhaseRecords(workflowId)
        ?.get(lastResumableStep)
        ?.failureDisposition
    if (disposition == FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION) {
      return false
    }
  }
  return true
}

fun supervisionEvent(
  reason: GoalRunnerStopReason,
  knownWorkflowId: String,
  progress: GoalRunnerWorkflowProgress?,
  liveness: GoalRunnerLivenessSnapshot?,
): GoalRunnerSupervisionEvent =
  GoalRunnerSupervisionEvent(
    phase = "goal_runner_supervision",
    reason = reason.name.lowercase(),
    continuationMode =
      when (reason) {
        GoalRunnerStopReason.TIMEOUT -> GoalRunnerContinuationMode.KILLED_UNRESPONSIVE_CHILD
        GoalRunnerStopReason.INTERRUPTED -> GoalRunnerContinuationMode.KILLED_BY_PARENT_INTERRUPT
        GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> GoalRunnerContinuationMode.CONTINUE_INLINE
        GoalRunnerStopReason.FAILED,
        GoalRunnerStopReason.BLOCKED,
        GoalRunnerStopReason.POLICY_BLOCKED,
        GoalRunnerStopReason.PULL_REQUEST_FAILED,
        GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
        GoalRunnerStopReason.RECONCILED_RESUMABLE,
        GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
        GoalRunnerStopReason.PAUSED,
        -> GoalRunnerContinuationMode.NONE
      },
    processState = liveness?.processState ?: GoalRunnerProcessState.UNKNOWN,
    workflowId = knownWorkflowId,
    stepId = progress?.currentStepId ?: liveness?.workflowStep,
    lastDurableProgress = progress?.latestLivenessSignal ?: liveness?.lastDurableProgressLabel,
    lastWorkflowSnapshotAt = progress?.lastSnapshotUpdatedAt ?: liveness?.lastWorkflowSnapshotAt,
    lastFileActivityAt = liveness?.lastFileActivityAt,
    lastOutputAt = liveness?.lastOutputAt,
  )
