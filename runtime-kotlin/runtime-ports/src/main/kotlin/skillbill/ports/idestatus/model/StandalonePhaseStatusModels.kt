package skillbill.ports.idestatus.model

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
)
