package skillbill.goalrunner.model

import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.decompositionStatus

internal data class GoalRunnerStatusProjectionContext(
  val currentSubtask: DecompositionSubtask?,
  val statusOf: (DecompositionSubtask) -> DecompositionStatus?,
  val staleSignal: Boolean,
)

internal fun buildGoalRunnerStatusProjectionContext(
  currentSubtask: DecompositionSubtask?,
  extras: GoalRunnerStatusProjectionRuntimeInputs,
): GoalRunnerStatusProjectionContext {
  val statusOf: (DecompositionSubtask) -> DecompositionStatus? = { subtask ->
    if (subtask.id == currentSubtask?.id && extras.currentWorkflowStatus in LIVE_WORKFLOW_STATUSES) {
      DecompositionStatus.IN_PROGRESS
    } else {
      subtask.status.decompositionStatus()
    }
  }
  val liveChild = extras.currentWorkflowStatus in LIVE_WORKFLOW_STATUSES
  val liveStep = extras.currentStepOverride?.takeIf(String::isNotBlank)
  val eventPhase = extras.latestObservabilityEvent?.workflowPhase?.takeIf(String::isNotBlank)
  val blockEvent = extras.latestObservabilityEvent?.livenessClass == "block"
  val supersededPhaseEvent = liveStep != null && eventPhase != null && eventPhase != liveStep
  val staleSignal = liveChild && (blockEvent || supersededPhaseEvent)
  return GoalRunnerStatusProjectionContext(currentSubtask, statusOf, staleSignal)
}

internal fun assembleGoalRunnerStatusProjection(
  issueKey: String,
  counts: GoalRunnerStatusCounts,
  activeAgent: String?,
  extras: GoalRunnerStatusProjectionRuntimeInputs,
  context: GoalRunnerStatusProjectionContext,
): GoalRunnerStatusProjection {
  val currentSubtask = context.currentSubtask
  val statusOf = context.statusOf
  return GoalRunnerStatusProjection(
    issueKey = issueKey,
    completeCount = counts.complete,
    pendingCount = counts.pending,
    blockedCount = counts.blocked,
    currentSubtaskId = currentSubtask?.id,
    currentChildWorkflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank),
    currentSubtaskStatus = currentSubtask?.let(statusOf),
    currentSubtaskBlockedReason = currentSubtask?.blockedReason?.takeIf(String::isNotBlank),
    currentStep =
      extras.currentStepOverride?.takeIf(String::isNotBlank)
        ?: currentSubtask?.lastResumableStep
        ?: currentSubtask?.let { subtask ->
          if (subtask.workflowId.isNullOrBlank()) "pending_launch" else "initializing"
        },
    activeAgent = activeAgent?.takeIf(String::isNotBlank),
    executionLiveness = extras.executionLiveness,
    progressStall = extras.progressStall,
    planning = extras.planning,
    latestLivenessSignal = extras.latestLivenessSignal?.takeIf { it.isNotBlank() && !context.staleSignal },
    latestObservabilityEvent = extras.latestObservabilityEvent?.takeUnless { context.staleSignal },
    requestedDiffStat = extras.requestedDiffStat,
    selectedDiffHunks = extras.selectedDiffHunks,
    blockedAttemptCount = extras.blockedAttemptCount,
    supervisorKillCount = extras.supervisorKillCount,
    phaseAttemptCounts = extras.phaseAttemptCounts,
    cumulativeFixIterations = extras.cumulativeFixIterations,
    reAttemptCauseCounts = extras.reAttemptCauseCounts,
    findingsInScope = extras.findingsInScope,
    outOfBandAcceptances = extras.outOfBandAcceptances,
    completedSubtaskValidation = extras.completedSubtaskValidation,
    completedSubtaskPhaseResults = extras.completedSubtaskPhaseResults,
    goalQaResult = extras.goalQaResult,
    goalAssessmentResult = extras.goalAssessmentResult,
    paused = extras.paused,
    pauseRequested = extras.pauseRequested,
    pauseReason = extras.pauseReason,
    pausedAt = extras.pausedAt,
    stopAfterSubtaskId = extras.stopAfterSubtaskId,
    activeDurationMs = extras.activeDurationMs,
    activeDurationAsOf = extras.activeDurationAsOf,
    subtaskActiveDurationMs = extras.subtaskActiveDurationMs,
    subtaskActiveDurationAsOf = extras.subtaskActiveDurationAsOf,
    degradedDurableRead = extras.degradedDurableRead,
    latestWorktreeEdit = extras.latestWorktreeEdit,
    auditAcRetryCount = extras.auditAcRetryCount,
    noChangeStatus = extras.noChangeStatus,
    goalCompletedAt = extras.goalCompletedAt,
  )
}

private val LIVE_WORKFLOW_STATUSES = setOf(WorkflowStatus.RUNNING, WorkflowStatus.PENDING)
