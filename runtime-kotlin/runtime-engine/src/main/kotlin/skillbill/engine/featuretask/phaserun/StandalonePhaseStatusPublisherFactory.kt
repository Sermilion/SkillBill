package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.OpenCodeLaunchIdentity
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
  public val acceptedIdentity: OpenCodeLaunchIdentity? = null,
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
    launch: StandalonePhaseLaunch = StandalonePhaseLaunch(),
  ): StandalonePhaseStatusEventSink {
    val registration =
      registrations.phaseRegistration(repoRoot, phaseId, invocationId, issueKey = null, agentId = launch.agentId)
        .let { current ->
          current.copy(
            launchAgentId = launch.agentId,
            launchArguments = launch.arguments,
            openCodeIdentity = launch.acceptedIdentity ?: current.openCodeIdentity,
          )
        }
    return StandalonePhaseStatusEventSink(
      database,
      { registration },
      clock,
      diagnostics,
      blockIncidentCapture,
      openCodeIdentity = registration.openCodeIdentity,
    )
  }

  internal fun forMonitor(
    attempt: MonitorAttemptTarget,
    phaseId: String,
    invocationId: String,
    launch: StandalonePhaseLaunch = StandalonePhaseLaunch(),
  ): StandalonePhaseStatusEventSink {
    val registration =
      registrations.monitorRegistration(attempt, phaseId, invocationId, launch.agentId)
        .let { current ->
          current.copy(
            launchAgentId = launch.agentId,
            launchArguments = launch.arguments,
            openCodeIdentity = launch.acceptedIdentity ?: current.openCodeIdentity,
          )
        }
    return StandalonePhaseStatusEventSink(
      database,
      { registration },
      clock,
      diagnostics,
      blockIncidentCapture,
      openCodeIdentity = registration.openCodeIdentity,
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
      registrations.phaseRegistration(repoRoot, phaseId, invocationId, issueKey, launch.agentId)
        .copy(launchAgentId = launch.agentId, launchArguments = launch.arguments, launchScope = launch.scope)
    return StandalonePhaseStatusEventSink(
      database,
      { workflowId -> base.copy(workflowId = workflowId) },
      clock,
      diagnostics,
      blockIncidentCapture,
      outputScheduler,
      capturePlanOutput = true,
      openCodeIdentity = base.openCodeIdentity,
    )
  }

  public fun registerWorkflow(
    repoRoot: Path,
    issueKey: String?,
    workflowId: String,
    launch: WorkflowStatusLaunch = WorkflowStatusLaunch(),
  ): String? {
    var identityConflict: String? = null
    catchingNonCancellation {
      val registration =
        registrations.workflowRegistration(repoRoot, issueKey, workflowId, launch)
      database.transaction { unitOfWork ->
        val statuses = unitOfWork.standalonePhaseStatuses
        val existing = statuses.latestWorkflowExecution(workflowId)
        val retained = existing?.openCodeIdentity
        identityConflict =
          when {
            retained != null && registration.openCodeIdentity == null ->
              "The accepted OpenCode provider identity conflicts with the requested provider. " +
                "Restore OpenCode and resume again."
            retained != null -> retained.conflictsWith(requireNotNull(registration.openCodeIdentity))
            else -> null
          }
        if (identityConflict == null) {
          val acceptedIdentity = if (existing == null) registration.openCodeIdentity else retained
          statuses.registerWorkflow(registration.copy(openCodeIdentity = acceptedIdentity))
        }
      }
    }.onFailure { error ->
      identityConflict =
        "Could not retain the accepted OpenCode identity before launch. " +
        "Retry after the runtime store is available."
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status workflow registration failed", error)
    }
    return identityConflict
  }

  public fun retainedOpenCodeIdentity(workflowId: String): OpenCodeLaunchIdentity? =
    database.transaction { unitOfWork ->
      unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(workflowId)?.openCodeIdentity
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
