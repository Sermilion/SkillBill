package skillbill.ports.idestatus.model

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.time.Instant

data class IdeStatusWorkflowRegistration(
  val repositoryIdentity: String,
  val branchCorrelation: String,
  val issueKey: String?,
  val workflowId: String,
  val invocationId: String,
  val executionId: String,
  val lifecycleState: String,
  val startedAt: Instant,
  val agentEnvironment: Map<String, String> = emptyMap(),
)

data class IdeStatusWorkflowExecution(
  val repositoryIdentity: String,
  val branchCorrelation: String,
  val issueKey: String?,
  val workflowId: String,
  val invocationId: String,
  val executionId: String,
  val statusStoreId: String,
  val runSequence: String,
  val statusRevision: String,
  val lifecycleState: String,
  val startedAt: Instant,
  val updatedAt: Instant,
  val agentEnvironment: Map<String, String> = emptyMap(),
)

data class StandalonePhaseStatusRegistration(
  val repositoryIdentity: String,
  val branchCorrelation: String,
  val issueKey: String?,
  val workflowId: String?,
  val invocationId: String,
  val phaseId: String,
  val executionId: String,
  val lifecycleState: String,
  val currentStep: String,
  val startedAt: Instant,
  val leaseOwner: String,
  val leaseGeneration: Long,
  val leaseExpiresAt: Instant,
  val processOwner: FeatureTaskRuntimeProcessIdentity? = null,
  val launchAgentId: String? = null,
  val launchArguments: List<String>? = null,
  val agentEnvironment: Map<String, String> = emptyMap(),
)

data class StandalonePhaseStatusUpdate(
  val executionId: String,
  val expectedRevision: String,
  val leaseOwner: String,
  val leaseGeneration: Long,
  val lifecycleState: String,
  val currentStep: String,
  val currentActivity: String?,
  val updatedAt: Instant,
  val finishedAt: Instant? = null,
  val activeDurationMs: Long? = null,
  val activeDurationAsOf: Instant? = null,
  val terminalResult: String? = null,
  val acceptedResult: String? = null,
)

enum class StandalonePhaseStatusUpdateResult {
  ACCEPTED,
  IDEMPOTENT,
  STALE_REVISION,
  STALE_LEASE,
  TERMINAL_REGRESSION,
  MISSING,
}

data class StandalonePhaseStatusRecord(
  val repositoryIdentity: String,
  val branchCorrelation: String,
  val issueKey: String?,
  val workflowId: String?,
  val invocationId: String,
  val phaseId: String,
  val executionId: String,
  val statusStoreId: String,
  val runSequence: String,
  val statusRevision: String,
  val lifecycleState: String,
  val currentStep: String,
  val currentActivity: String?,
  val startedAt: Instant,
  val updatedAt: Instant,
  val finishedAt: Instant?,
  val activeDurationMs: Long?,
  val activeDurationAsOf: Instant?,
  val leaseOwner: String,
  val leaseGeneration: Long,
  val leaseExpiresAt: Instant,
  val terminalResult: String?,
  val acceptedResult: String? = null,
  val processOwner: FeatureTaskRuntimeProcessIdentity? = null,
  val launchAgentId: String? = null,
  val launchArguments: List<String>? = null,
  val agentEnvironment: Map<String, String> = emptyMap(),
)

const val STANDALONE_ELIGIBLE_PAGE_LIMIT: Int = 500

enum class StandaloneEligibleSlice { LIVE, SETTLED }

data class StandalonePhaseExecutionAnchor(
  val runSequence: String,
  val invocationId: String,
)

data class StandaloneEligiblePage(
  val limit: Int = STANDALONE_ELIGIBLE_PAGE_LIMIT,
  val after: StandalonePhaseExecutionAnchor? = null,
  val slice: StandaloneEligibleSlice? = null,
)

data class StandalonePhaseExecutionQuery(
  val repositoryIdentity: String,
  val phaseId: String,
  val phaseGroup: String,
  val startedThrough: Instant,
  val offset: Int,
  val limit: Int,
  val after: StandalonePhaseExecutionAnchor? = null,
)
