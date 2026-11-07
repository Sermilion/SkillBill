package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.monitor.MonitorAttemptTarget
import skillbill.error.core.catchingNonCancellation
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.StandaloneOutputCaptureScheduler
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

public data class StandalonePhaseLaunch(
  public val agentId: String? = null,
  public val arguments: List<String>? = null,
  public val scope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
)

@Inject
public class StandalonePhaseStatusPublisherFactory(
  private val database: DatabaseSessionFactory,
  private val registrations: StandalonePhaseRegistrationFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val blockIncidentCapture: BlockIncidentCapture,
  private val outputScheduler: StandaloneOutputCaptureScheduler?,
) {
  public fun forPhase(
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

  public fun forPlan(
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
      outputScheduler,
      capturePlanOutput = true,
    )
  }

  public fun registerWorkflow(
    repoRoot: Path,
    issueKey: String?,
    workflowId: String,
    workBranch: String? = null,
    goalScoped: Boolean = false,
  ) {
    catchingNonCancellation {
      val registration = registrations.workflowRegistration(repoRoot, issueKey, workflowId, workBranch, goalScoped)
      database.transaction { unitOfWork -> unitOfWork.standalonePhaseStatuses.registerWorkflow(registration) }
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status workflow registration failed", error)
    }
  }

  public fun compose(
    stdout: (FeatureTaskRuntimeRunEvent) -> Unit,
    status: StandalonePhaseStatusEventSink,
  ): FeatureTaskRuntimeRunEventSink =
    FeatureTaskRuntimeRunEventSink { event ->
      status.emit(event)
      stdout(event)
    }
}
