package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.BlockIncidentCapture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.monitor.MonitorAttemptTarget
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CancellationException

data class StandalonePhaseLaunch(
  val agentId: String? = null,
  val arguments: List<String>? = null,
  val scope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
)

class StandalonePhaseStatusPublisherFactory(
  private val database: DatabaseSessionFactory,
  private val registrations: StandalonePhaseRegistrationFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val blockIncidentCapture: BlockIncidentCapture,
) {
  fun forPhase(
    repoRoot: Path,
    phaseId: String,
    invocationId: String,
    agentId: String? = null,
    launchArguments: List<String>? = null,
  ): StandalonePhaseStatusEventSink {
    val registration =
      registrations.phaseRegistration(repoRoot, phaseId, invocationId, issueKey = null)
        .copy(launchAgentId = agentId, launchArguments = launchArguments)
    return StandalonePhaseStatusEventSink(database, { registration }, clock, diagnostics, blockIncidentCapture)
  }

  internal fun forMonitor(
    attempt: MonitorAttemptTarget,
    phaseId: String,
    invocationId: String,
    agentId: String? = null,
    launchArguments: List<String>? = null,
  ): StandalonePhaseStatusEventSink {
    val registration =
      registrations.monitorRegistration(attempt, phaseId, invocationId)
        .copy(launchAgentId = agentId, launchArguments = launchArguments)
    return StandalonePhaseStatusEventSink(
      database,
      { registration },
      clock,
      diagnostics,
      blockIncidentCapture,
    )
  }

  fun forPlan(
    repoRoot: Path,
    issueKey: String,
    phaseId: String,
    launch: StandalonePhaseLaunch = StandalonePhaseLaunch(),
  ): StandalonePhaseStatusEventSink {
    val invocationId = "plan-${UUID.randomUUID()}"
    val base =
      registrations.phaseRegistration(repoRoot, phaseId, invocationId, issueKey)
        .copy(launchAgentId = launch.agentId, launchArguments = launch.arguments, launchScope = launch.scope)
    return StandalonePhaseStatusEventSink(
      database,
      { workflowId -> base.copy(workflowId = workflowId) },
      clock,
      diagnostics,
      blockIncidentCapture,
    )
  }

  fun registerWorkflow(
    repoRoot: Path,
    issueKey: String?,
    workflowId: String,
    workBranch: String? = null,
  ) {
    runCatching {
      val registration = registrations.workflowRegistration(repoRoot, issueKey, workflowId, workBranch)
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
