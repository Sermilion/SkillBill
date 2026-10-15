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
)

/** Most eligible standalone rows one status read pulls for a branch; a longer tail is reached by anchored paging. */
const val STANDALONE_ELIGIBLE_PAGE_LIMIT: Int = 500

/**
 * Which eligibility disjunct an eligible read returns: executions still live inside the live retention window, or
 * executions settled inside the settled window. A status read pages the live slice to its end before the settled slice,
 * so a long settled tail never starves a live execution of the page budget.
 */
enum class StandaloneEligibleSlice { LIVE, SETTLED }

/**
 * The execution a history page continues after. Executions are ordered newest first by run sequence, so a page anchored
 * on one never repeats or skips a run when newer runs are recorded between reads.
 */
data class StandalonePhaseExecutionAnchor(
  val runSequence: String,
  val invocationId: String,
)

/** A history page starts [offset] rows in, or strictly after [after] when the caller holds an anchor; never both. */
data class StandalonePhaseExecutionQuery(
  val repositoryIdentity: String,
  val phaseId: String,
  val phaseGroup: String,
  val startedThrough: Instant,
  val offset: Int,
  val limit: Int,
  val after: StandalonePhaseExecutionAnchor? = null,
)
