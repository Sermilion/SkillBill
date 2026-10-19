package skillbill.engine.goalrunner.status

import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.model.FeatureTaskWorkflowMode

internal fun resolveRuntimeWorkflowExecutionLiveness(
  workflowId: String,
  phaseQuery: FeatureTaskRuntimePhaseQuery,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
): ExecutionLiveness {
  if (phaseQuery.existingWorkflowMode(workflowId) != FeatureTaskWorkflowMode.RUNTIME) {
    return ExecutionLiveness.UNKNOWN
  }
  val ownership = phaseQuery.workerOwnership(workflowId) ?: return ExecutionLiveness.IDLE
  val inspection = workerSupervisor.inspect(ownership)
  return executionLivenessFromWorkerInspection(inspection)
}

internal fun executionLivenessFromWorkerInspection(
  inspection: FeatureTaskRuntimeProcessInspection,
): ExecutionLiveness =
  when (inspection) {
    FeatureTaskRuntimeProcessInspection.NotRunning -> ExecutionLiveness.IDLE
    FeatureTaskRuntimeProcessInspection.ForeignHost -> ExecutionLiveness.FOREIGN_HOST
    FeatureTaskRuntimeProcessInspection.PidReused -> ExecutionLiveness.PID_REUSED
    is FeatureTaskRuntimeProcessInspection.Unsupported -> ExecutionLiveness.UNKNOWN
    FeatureTaskRuntimeProcessInspection.ExactLive -> ExecutionLiveness.LIVE
  }

internal fun executionLivenessBlocksIdleSafeRefresh(liveness: ExecutionLiveness): Boolean =
  when (liveness) {
    ExecutionLiveness.IDLE -> false
    ExecutionLiveness.LIVE,
    ExecutionLiveness.LIVE_EXPIRED,
    ExecutionLiveness.UNKNOWN,
    ExecutionLiveness.FOREIGN_HOST,
    ExecutionLiveness.PID_REUSED,
    -> true
  }
