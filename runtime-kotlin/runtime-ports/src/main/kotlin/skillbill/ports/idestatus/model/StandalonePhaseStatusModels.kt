package skillbill.ports.idestatus.model

import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.monitor.model.MonitorRetentionReason
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

data class StandaloneMonitorTarget(
  val pullRequestKey: String,
  val pullRequestUrl: String,
  val headRepository: String,
  val headRef: String,
  val callerRoot: String,
  val worktreePath: String?,
) {
  init {
    val match =
      requireNotNull(PULL_REQUEST_KEY.matchEntire(pullRequestKey)) {
        "Monitor pull request key '$pullRequestKey' is not host/owner/name#number."
      }
    val (location, number) = match.destructured
    require(pullRequestUrl == "https://$location/pull/$number") {
      "Monitor pull request URL '$pullRequestUrl' does not match key '$pullRequestKey'."
    }
    require(
      headRepository.isNotBlank() && headRef.isNotBlank() && callerRoot.isNotBlank() &&
        (worktreePath == null || worktreePath.isNotBlank()),
    ) {
      "A monitor target needs a head repository, head ref and caller root; worktree path must be nonblank if present."
    }
  }

  private companion object {
    val PULL_REQUEST_KEY = Regex("([^/#\\s]+/[^/#\\s]+/[^/#\\s]+)#([1-9][0-9]*)")
  }
}

sealed interface StandaloneMonitorCleanup {
  data object Released : StandaloneMonitorCleanup

  data class Retained(
    val reason: MonitorRetentionReason,
    val detail: String,
  ) : StandaloneMonitorCleanup {
    init {
      require(detail.isNotBlank()) { "A retained monitor worktree needs a detail." }
    }
  }
}

enum class StandaloneQaOutcome(val wireValue: String) {
  ATTEMPTED("attempted"),
  PASSED("passed"),
  FINDINGS("findings"),
  SKIPPED("skipped"),
  INCOMPLETE("incomplete"),
  ;

  companion object {
    fun fromWire(value: String?): StandaloneQaOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

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
  val launchScope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
  val monitorTarget: StandaloneMonitorTarget? = null,
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
  val monitorCleanup: StandaloneMonitorCleanup? = null,
  val qaOutcome: StandaloneQaOutcome? = null,
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
  val launchScope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
  val monitorTarget: StandaloneMonitorTarget? = null,
  val monitorCleanup: StandaloneMonitorCleanup? = null,
  val qaOutcome: StandaloneQaOutcome? = null,
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
