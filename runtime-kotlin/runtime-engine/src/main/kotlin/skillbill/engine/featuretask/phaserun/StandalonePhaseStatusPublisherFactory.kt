package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CancellationException

class StandalonePhaseStatusPublisherFactory(
  private val database: DatabaseSessionFactory,
  private val registrations: StandalonePhaseRegistrationFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun forPhase(
    repoRoot: Path,
    phaseId: String,
    invocationId: String,
  ): StandalonePhaseStatusEventSink {
    val registration = registrations.phaseRegistration(repoRoot, phaseId, invocationId, issueKey = null)
    return StandalonePhaseStatusEventSink(database, { registration }, clock, diagnostics)
  }

  fun forPlan(
    repoRoot: Path,
    issueKey: String,
    phaseId: String,
  ): StandalonePhaseStatusEventSink {
    val invocationId = "plan-${UUID.randomUUID()}"
    val base = registrations.phaseRegistration(repoRoot, phaseId, invocationId, issueKey)
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
      val registration = registrations.workflowRegistration(repoRoot, issueKey, workflowId)
      database.transaction { unitOfWork -> unitOfWork.standalonePhaseStatuses.registerWorkflow(registration) }
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
}
