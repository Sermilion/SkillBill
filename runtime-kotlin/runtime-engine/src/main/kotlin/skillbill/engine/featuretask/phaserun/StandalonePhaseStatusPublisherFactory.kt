package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

class StandalonePhaseStatusPublisherFactory(
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val branchSource: CheckedOutBranchSource,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun forPhase(
    repoRoot: Path,
    phaseId: String,
    invocationId: String,
  ): StandalonePhaseStatusEventSink {
    val registration = registration(repoRoot, phaseId, invocationId, issueKey = null)
    return StandalonePhaseStatusEventSink(database, { registration }, clock, diagnostics)
  }

  fun forPlan(
    repoRoot: Path,
    issueKey: String,
    phaseId: String,
  ): StandalonePhaseStatusEventSink {
    val invocationId = "plan-${UUID.randomUUID()}"
    val base = registration(repoRoot, phaseId, invocationId, issueKey)
    return StandalonePhaseStatusEventSink(
      database,
      { workflowId -> base.copy(workflowId = workflowId) },
      clock,
      diagnostics,
    )
  }

  fun registerWorkflow(
    repoRoot: Path,
    issueKey: String?,
    workflowId: String,
  ) {
    runCatching {
      val canonicalRoot = repositories.canonicalPath(repoRoot)
      database.transaction { unitOfWork ->
        unitOfWork.standalonePhaseStatuses.registerWorkflow(
          IdeStatusWorkflowRegistration(
            repositoryIdentity = repositories.repositoryIdentity(canonicalRoot),
            branchCorrelation = branchSource.checkedOutBranch(canonicalRoot) ?: "HEAD",
            issueKey = issueKey,
            workflowId = workflowId,
            invocationId = "workflow-$workflowId-${UUID.randomUUID()}",
            executionId = "workflow-$workflowId-${UUID.randomUUID()}",
            lifecycleState = "active",
            startedAt = clock.instant(),
          ),
        )
      }
    }.onFailure { error ->
      if (error is CancellationException) throw error
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status workflow registration failed", error)
    }
  }

  fun compose(
    stdout: (FeatureTaskRuntimeRunEvent) -> Unit,
    status: StandalonePhaseStatusEventSink,
  ): FeatureTaskRuntimeRunEventSink =
    FeatureTaskRuntimeRunEventSink { event ->
      stdout(event)
      status.emit(event)
    }

  private fun registration(
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
      leaseExpiresAt = leaseExpiresAt(phaseId, now),
    )
  }
}

private fun leaseExpiresAt(
  phaseId: String,
  now: Instant,
): Instant =
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR) {
    MONITOR_LEASE_HORIZON
  } else {
    now.plus(LEASE_DURATION)
  }

private val LEASE_DURATION: Duration = Duration.ofDays(1)

private val MONITOR_LEASE_HORIZON: Instant = Instant.parse("9999-12-31T00:00:00Z")
