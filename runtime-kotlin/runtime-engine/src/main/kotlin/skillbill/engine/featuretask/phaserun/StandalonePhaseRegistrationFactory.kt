package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CancellationException

@Inject
class StandalonePhaseRegistrationFactory(
  private val repositories: RepositoryEnclosingRootPort,
  private val branchSource: CheckedOutBranchSource,
  private val clock: Clock,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
  private val environment: EnvironmentContext,
) {
  fun workflowRegistration(
    repoRoot: Path,
    issueKey: String?,
    workflowId: String,
    workBranch: String? = null,
  ): IdeStatusWorkflowRegistration {
    val canonicalRoot = repositories.canonicalPath(repoRoot)
    return IdeStatusWorkflowRegistration(
      repositoryIdentity = repositories.repositoryIdentity(canonicalRoot),
      branchCorrelation = workBranch ?: branchSource.checkedOutBranch(canonicalRoot) ?: "HEAD",
      issueKey = issueKey,
      workflowId = workflowId,
      invocationId = "workflow-$workflowId-${UUID.randomUUID()}",
      executionId = "workflow-$workflowId-${UUID.randomUUID()}",
      lifecycleState = "active",
      startedAt = clock.instant(),
      agentEnvironment = agentEnvironment(),
    )
  }

  fun phaseRegistration(
    repoRoot: Path,
    phaseId: String,
    invocationId: String,
    issueKey: String?,
  ): StandalonePhaseStatusRegistration {
    val canonicalRoot = repositories.canonicalPath(repoRoot)
    val now = clock.instant()
    return StandalonePhaseStatusRegistration(
      repositoryIdentity = repositories.repositoryIdentity(canonicalRoot),
      branchCorrelation = branchSource.checkedOutBranch(canonicalRoot) ?: "HEAD",
      issueKey = issueKey,
      workflowId = null,
      invocationId = invocationId,
      phaseId = phaseId,
      executionId = "phase-$invocationId",
      lifecycleState = "active",
      currentStep = phaseId,
      startedAt = now,
      leaseOwner = invocationId,
      leaseGeneration = 1L,
      leaseExpiresAt = now.plus(LEASE_DURATION),
      processOwner = processOwner(),
      agentEnvironment = agentEnvironment(),
    )
  }

  private fun agentEnvironment(): Map<String, String> =
    AgentHomeEnvironmentKeys.ALL.mapNotNull { key ->
      environment.environment[key]?.takeIf(String::isNotBlank)?.let { key to it }
    }.toMap()

  private fun processOwner(): FeatureTaskRuntimeProcessIdentity? =
    runCatching {
      workerSupervisor.currentProcess()
    }.getOrElse { failure ->
      if (failure is CancellationException) throw failure
      if (failure is InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
      }
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=standalone-status.process-owner outcome=unavailable cause=${failure.javaClass.simpleName}",
      )
      null
    }
}

private val LEASE_DURATION: Duration = Duration.ofDays(1)
