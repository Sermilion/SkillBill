package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.BlockIncidentCapture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.monitor.MonitorAttemptTarget
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CancellationException

data class StandalonePhaseStatusLaunch(
  val phaseId: String,
  val invocationId: String,
  val agentId: String? = null,
  val launchArguments: List<String>? = null,
  val monitorSelector: String? = null,
)

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
    launch: StandalonePhaseStatusLaunch,
  ): StandalonePhaseStatusEventSink =
    sink(registrations.phaseRegistration(repoRoot, launch.phaseId, launch.invocationId, issueKey = null), launch)

  internal fun forMonitor(
    attempt: MonitorAttemptTarget,
    launch: StandalonePhaseStatusLaunch,
  ): StandalonePhaseStatusEventSink =
    sink(registrations.monitorRegistration(attempt, launch.phaseId, launch.invocationId), launch)

  private fun sink(
    base: StandalonePhaseStatusRegistration,
    launch: StandalonePhaseStatusLaunch,
  ): StandalonePhaseStatusEventSink {
    val registration = base.copy(launchAgentId = launch.agentId, launchArguments = launch.launchArguments)
    return StandalonePhaseStatusEventSink(
      database,
      { registration },
      clock,
      diagnostics,
      blockIncidentCapture,
      launch.monitorSelector,
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
