package skillbill.goalrunner.model

import skillbill.workflow.model.goalobservability.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.goalobservability.GoalObservabilityDiffStat
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

data class GoalRunnerObservabilityRecordRequest(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val timestamp: String,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(workflowPhase.isNotBlank()) { "workflowPhase is required." }
    require(workerRole.isNotBlank()) { "workerRole is required." }
    require(livenessClass.isNotBlank()) { "livenessClass is required." }
    require(activitySummary.isNotBlank()) { "activitySummary is required." }
    require(timestamp.isNotBlank()) { "timestamp is required." }
  }
}

data class GoalRunnerProgressEvent(
  val stepId: String,
  val attemptCount: Int,
  val kind: String,
  val message: String,
  val sequence: Int,
  val timestamp: String,
)

data class GoalObservabilityProgressEvent(
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val sequenceNumber: Int,
  val timestamp: String,
) {
  fun toObservabilityEvent(): GoalObservabilityEvent =
    GoalObservabilityEvent(
      issueKey = issueKey,
      subtaskId = subtaskId,
      workflowPhase = workflowPhase,
      workerRole = workerRole,
      livenessClass = livenessClass,
      activitySummary = activitySummary,
      timestamp = timestamp,
      sequenceNumber = sequenceNumber,
    )
}

data class GoalRunnerAttemptLedgerSummary(
  val blockedAttemptCount: Int = 0,
  val supervisorKillCount: Int = 0,
  val phaseAttemptCounts: Map<String, Int> = emptyMap(),
  val cumulativeFixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauseCounts: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
)

data class GoalContinuation(
  val issueKey: String,
  val subtaskId: Int,
  val suppressPr: Boolean,
  val goalBranch: String?,
)

data class GoalObservabilityWorktreeActivity(
  val changedFileSummary: GoalObservabilityChangedFileSummary?,
  val diffStat: GoalObservabilityDiffStat?,
)

data class GoalObservabilityProgressInput(
  val artifacts: FeatureTaskRuntimeWorkflowArtifactMap,
  val workflowId: String,
  val workflowStatus: String,
  val currentStepId: String,
  val worktreeActivity: GoalObservabilityWorktreeActivity? = null,
)

data class GoalObservabilityRuntimeEventInput(
  val artifacts: FeatureTaskRuntimeWorkflowArtifactMap,
  val request: GoalRunnerObservabilityRecordRequest,
)
