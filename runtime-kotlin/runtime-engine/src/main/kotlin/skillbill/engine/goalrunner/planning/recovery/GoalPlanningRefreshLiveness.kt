package skillbill.engine.goalrunner.planning.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.telemetry.GoalRunnerBestEffortEmission
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.time.Clock

fun interface GoalPlanningRefreshLiveness {
  fun resolve(state: GoalRunnerManifestState): ExecutionLiveness
}

@Inject
class ChildAwareGoalPlanningRefreshLiveness(
  private val phaseQuery: FeatureTaskRuntimePhaseQuery,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : GoalPlanningRefreshLiveness {
  override fun resolve(state: GoalRunnerManifestState): ExecutionLiveness {
    val currentSubtask =
      state.manifest.subtasks.firstOrNull { subtask ->
        subtask.id == state.manifest.currentSubtaskIntent.subtaskId
      }
    return resolveChildExecutionLiveness(currentSubtask, phaseQuery, clock, diagnostics)
  }
}

fun resolveChildExecutionLiveness(
  currentSubtask: DecompositionSubtask?,
  phaseQuery: FeatureTaskRuntimePhaseQuery,
  clock: Clock,
  diagnostics: RuntimeDiagnostics,
): ExecutionLiveness {
  val workflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank) ?: return ExecutionLiveness.IDLE
  return GoalRunnerBestEffortEmission.runCancellable {
    if (phaseQuery.existingWorkflowMode(workflowId) != FeatureTaskWorkflowMode.RUNTIME) {
      ExecutionLiveness.UNKNOWN
    } else {
      val ownership = phaseQuery.workerOwnership(workflowId)
      if (ownership != null && ownership.expiresAtInstant.isAfter(clock.instant())) {
        ExecutionLiveness.LIVE
      } else {
        ExecutionLiveness.IDLE
      }
    }
  }.getOrElse { error ->
    GoalRunnerBestEffortEmission.rethrowIfCancellation(error)
    GoalRunnerBestEffortEmission.recordWarning(
      diagnostics,
      GoalRunnerBestEffortEmission.boundedMessage(
        "Degraded read at seam goal-planning.child_execution_liveness for workflow '$workflowId': " +
          "expected live_or_idle, used unknown.",
      ),
      error,
    )
    ExecutionLiveness.UNKNOWN
  }
}

fun refuseRefreshReason(
  issueKey: String,
  liveness: ExecutionLiveness,
): String? =
  when (liveness) {
    ExecutionLiveness.LIVE ->
      "Goal '$issueKey' is live; refuse shared-preplan refresh while the current child run is active."
    ExecutionLiveness.UNKNOWN ->
      "Goal '$issueKey' has unknown execution liveness; refuse shared-preplan refresh."
    ExecutionLiveness.IDLE -> null
  }
