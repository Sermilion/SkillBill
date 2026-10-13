package skillbill.engine.goalrunner.planning.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.status.executionLivenessBlocksIdleSafeRefresh
import skillbill.engine.goalrunner.status.resolveRuntimeWorkflowExecutionLiveness
import skillbill.engine.goalrunner.telemetry.GoalRunnerBestEffortEmission
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.time.Clock

fun interface GoalPlanningRefreshLiveness {
  fun resolve(state: GoalRunnerManifestState): ExecutionLiveness
}

@Inject
class ChildAwareGoalPlanningRefreshLiveness(
  private val phaseQuery: FeatureTaskRuntimePhaseQuery,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : GoalPlanningRefreshLiveness {
  override fun resolve(state: GoalRunnerManifestState): ExecutionLiveness {
    val currentSubtask =
      state.manifest.subtasks.firstOrNull { subtask ->
        subtask.id == state.manifest.currentSubtaskIntent.subtaskId
      }
    return resolveChildExecutionLiveness(currentSubtask, phaseQuery, workerSupervisor, clock, diagnostics)
  }
}

fun resolveChildExecutionLiveness(
  currentSubtask: DecompositionSubtask?,
  phaseQuery: FeatureTaskRuntimePhaseQuery,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  clock: Clock,
  diagnostics: RuntimeDiagnostics,
): ExecutionLiveness {
  val workflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank) ?: return ExecutionLiveness.IDLE
  return GoalRunnerBestEffortEmission.runCancellable {
    resolveRuntimeWorkflowExecutionLiveness(workflowId, phaseQuery, workerSupervisor, clock)
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
    ExecutionLiveness.LIVE_EXPIRED ->
      "Goal '$issueKey' has a live-but-expired child lease; refuse shared-preplan refresh."
    ExecutionLiveness.UNKNOWN ->
      "Goal '$issueKey' has unknown execution liveness; refuse shared-preplan refresh."
    ExecutionLiveness.FOREIGN_HOST ->
      "Goal '$issueKey' has foreign-host child ownership; refuse shared-preplan refresh."
    ExecutionLiveness.PID_REUSED ->
      "Goal '$issueKey' has pid-reused child ownership; refuse shared-preplan refresh."
    ExecutionLiveness.IDLE -> null
  }

fun executionLivenessRefusesIdleScopedReplan(liveness: ExecutionLiveness): Boolean =
  executionLivenessBlocksIdleSafeRefresh(liveness)
