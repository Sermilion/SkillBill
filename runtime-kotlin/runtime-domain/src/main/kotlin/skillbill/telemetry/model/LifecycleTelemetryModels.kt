package skillbill.telemetry.model

data class FeatureTaskRuntimeStartedRecord(
  val sessionId: String,
  val featureSize: String,
  val issueKey: String,
  val featureName: String,
  val workflowId: String = "",
  val goalParentWorkflowId: String? = null,
  val goalSubtaskId: Int? = null,
)

data class FeatureTaskRuntimeFinishedRecord(
  val sessionId: String,
  val completionStatus: String,
  val completedPhaseIds: List<String>,
  val phaseOutcomes: Map<String, String>,
  val lastIncompletePhase: String,
  val blockedReason: String,
  val resolvedBranch: String,
  val reviewFixIterationCount: Int = 0,
  val regenerationActivationCount: Int = 0,
  val regenerationAttemptCount: Int = 0,
  val regenerationOutcomeCounts: Map<String, Int> = emptyMap(),
  val crashReconciliationCount: Int = 0,
  val crashReconciliationReasonCounts: Map<String, Int> = emptyMap(),
  val estimatedPhaseTokenBreakdownJson: String? = null,
  val estimatedTotalTokens: Int? = null,
  val findingVerificationVerifiedCount: Int = 0,
  val findingVerificationRejectedCount: Int = 0,
  val reviewFixCapExhausted: Boolean? = null,
  val auditGapIterationCount: Int? = null,
  val resolvedAgentIds: List<String>? = null,
  val launchedModels: List<String>? = null,
  val phaseStrategies: String? = null,
  val phaseStrategyAvailability: String? = null,
)

data class QualityCheckStartedRecord(
  val sessionId: String,
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean,
  val fallbackReason: String?,
  val scopeType: String,
  val initialFailureCount: Int,
)

data class QualityCheckFinishedRecord(
  val sessionId: String,
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean,
  val fallbackReason: String?,
  val scopeType: String,
  val initialFailureCount: Int,
  val finalFailureCount: Int,
  val iterations: Int,
  val result: String,
  val failingCheckNames: List<String>,
  val unsupportedReason: String,
)

data class FeatureVerifyStartedRecord(
  val sessionId: String,
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
)

data class FeatureVerifyFinishedRecord(
  val sessionId: String,
  val featureFlagAuditPerformed: Boolean,
  val reviewIterations: Int,
  val auditResult: String,
  val completionStatus: String,
  val historyRelevance: String,
  val historyHelpfulness: String,
  val gapsFound: List<String>,
)

data class PrDescriptionGeneratedRecord(
  val sessionId: String,
  val commitCount: Int,
  val filesChangedCount: Int,
  val wasEditedByUser: Boolean,
  val prCreated: Boolean,
  val prTitle: String,
)

data class GoalStartedRecord(
  val issueKey: String,
  val featureName: String,
  val workflowId: String,
  val subtaskTotal: Int,
  val resumed: Boolean,
  val startedAt: String,
  val status: String = "running",
  val mode: String,
  val parentWorkflowId: String? = null,
)

data class GoalSubtaskFinishedRecord(
  val issueKey: String,
  val workflowId: String,
  val subtaskId: Int,
  val subtaskName: String,
  val status: String,
  val startedAt: String,
  val finishedAt: String,
  val durationMs: Long,
  val attemptCount: Int,
  val blockedReason: String?,
  val finalizingAgentId: String? = null,
  val participatingAgentIds: List<String> = emptyList(),
)

data class GoalFinishedRecord(
  val issueKey: String,
  val workflowId: String,
  val status: String,
  val startedAt: String,
  val finishedAt: String,
  val durationMs: Long,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val mode: String,
  val stopReason: String? = null,
  val noChangeReason: String? = null,
  val parentWorkflowId: String? = null,
)

data class GoalIssueFinishedRecord(
  val issueKey: String,
  val parentWorkflowId: String,
  val status: String,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val finishedAt: String,
  val mode: String,
  val noChangeReason: String? = null,
)
