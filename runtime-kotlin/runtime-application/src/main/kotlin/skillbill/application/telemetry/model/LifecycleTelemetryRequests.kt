package skillbill.application.telemetry.model

data class FeatureTaskRuntimeStartedRequest(
  val featureSize: String,
  val issueKey: String,
  val featureName: String,
  val sessionId: String = "",
  val correlation: FeatureTaskRuntimeCorrelation = FeatureTaskRuntimeCorrelation(),
)

data class FeatureTaskRuntimeCorrelation(
  val workflowId: String = "",
  val goalParentWorkflowId: String? = null,
  val goalSubtaskId: Int? = null,
)

data class FeatureTaskRuntimeFinishedRequest(
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
  val agentContext: FeatureTaskRuntimeAgentContext = FeatureTaskRuntimeAgentContext(),
  val phaseStrategies: String? = null,
  val phaseStrategyAvailability: String? = null,
)

data class FeatureTaskRuntimeAgentContext(
  val resolvedAgentIds: List<String>? = null,
  val launchedModels: List<String>? = null,
)

data class QualityCheckStartedRequest(
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean = false,
  val fallbackReason: String? = null,
  val scopeType: String,
  val initialFailureCount: Int,
  val orchestrated: Boolean,
)

data class QualityCheckFinishedRequest(
  val finalFailureCount: Int,
  val iterations: Int,
  val result: String,
  val sessionId: String,
  val failingCheckNames: List<String>,
  val unsupportedReason: String,
  val orchestrated: Boolean,
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean = false,
  val fallbackReason: String? = null,
  val scopeType: String,
  val initialFailureCount: Int,
  val durationSeconds: Int,
)

data class FeatureVerifyStartedRequest(
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
  val orchestrated: Boolean,
)

data class FeatureVerifyFinishedRequest(
  val featureFlagAuditPerformed: Boolean,
  val reviewIterations: Int,
  val auditResult: String,
  val completionStatus: String,
  val historyRelevance: String,
  val historyHelpfulness: String,
  val sessionId: String,
  val gapsFound: List<String>,
  val orchestrated: Boolean,
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
  val durationSeconds: Int,
)

data class PrDescriptionGeneratedRequest(
  val commitCount: Int,
  val filesChangedCount: Int,
  val wasEditedByUser: Boolean,
  val prCreated: Boolean,
  val prTitle: String,
  val orchestrated: Boolean,
  val generatedDescription: String? = null,
  val finalPrBody: String? = null,
)

data class GoalStartedRequest(
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

data class GoalSubtaskFinishedRequest(
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

data class GoalFinishedRequest(
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

data class GoalIssueFinishedRequest(
  val issueKey: String,
  val parentWorkflowId: String,
  val status: String,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val finishedAt: String,
  val mode: String,
)
