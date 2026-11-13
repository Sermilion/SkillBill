package skillbill.engine.goalrunner.status

import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.monitoring.isCiWaitingOperation
import skillbill.goalrunner.ledger.parseInstantOrNull
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.idestatus.model.WorktreeEditSummary
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import java.time.Instant

internal data class GoalProgressStallSignals(
  val progress: GoalRunnerWorkflowProgress?,
  val agentActivityStamps: List<Instant>,
  val latestWorktreeEdit: WorktreeEditSummary?,
) {
  fun latestProgressAt(): Instant? =
    (
      agentActivityStamps +
        listOfNotNull(
          progress?.latestDurableProgressEvent?.timestamp?.let(::parseInstantOrNull),
          progress?.latestDeclaredProgressEvent?.takeIf(::reportsProgress)?.timestamp,
          latestWorktreeEdit?.recordedAt,
          progress?.currentStepEnteredAt,
        )
    ).maxOrNull()

  private fun reportsProgress(event: GoalProgressEvent): Boolean =
    event.eventKind != GoalProgressEventKind.OPERATION_HEARTBEAT || isCiWaitingOperation(event.operationName)
}

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
