package skillbill.engine.featuretask.phase.core

import skillbill.engine.featuretask.model.execution.RecordedMonitorExecutionSemantics
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyDispatch
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

public data class MonitorRepairStepRoles(val monitor: String, val repair: String, val publication: String) {
  internal fun hasCorrectedProducerSemantics(
    recorded: RecordedMonitorExecutionSemantics?,
    supportedPlan: ResolvedPhaseExecutionPlan,
  ): Boolean {
    recorded ?: return false
    val selected = supportedPlan.selectedStrategies.singleOrNull { it.slot == PhaseSlot.MONITOR } ?: return false
    val monitorSteps = listOf(monitor, repair)
    val monitorOwner = ResolvedPhaseStrategyDispatch(selected.slot, selected.strategyId, selected.semanticRevision)
    val publicationOwner = supportedPlan.dispatchStrategyByStep[publication] ?: return false
    return recorded.monitorSelection == selected &&
      monitorSteps.all { step -> step in selected.steps && recorded.dispatchOwners[step] == monitorOwner } &&
      publicationOwner.slot == PhaseSlot.COMMIT_PUSH &&
      recorded.dispatchOwners[publication] == publicationOwner &&
      hasCorrectedMonitorEdges(recorded)
  }

  private fun hasCorrectedMonitorEdges(recorded: RecordedMonitorExecutionSemantics): Boolean {
    val monitorEdges =
      recorded.traversal.backwardEdges.filter {
        it.fromPhaseId == monitor && it.destinationPhaseId == repair
      }
    val expectedMonitorEdges =
      listOf(
        FeatureTaskRuntimeBackwardEdge(
          monitor,
          FeatureTaskRuntimeVerdict.CI_FAILED,
          repair,
          FeatureTaskRuntimePhaseWorkflowDefinition.MONITOR_FIX_LOOP_ID,
          FeatureTaskRuntimePhaseWorkflowDefinition.MAX_MONITOR_FIX_ATTEMPTS - 1,
        ),
        FeatureTaskRuntimeBackwardEdge(
          monitor,
          FeatureTaskRuntimeVerdict.PROGRESS,
          repair,
          FeatureTaskRuntimePhaseWorkflowDefinition.MONITOR_FIX_TRIGGER_LOOP_ID,
          null,
        ),
      )
    val publicationEdges =
      recorded.traversal.backwardEdges.filter {
        it.fromPhaseId == repair && it.destinationPhaseId == monitor
      }
    val expectedPublicationEdge =
      FeatureTaskRuntimeBackwardEdge(
        repair,
        FeatureTaskRuntimeVerdict.ADVANCE,
        monitor,
        FeatureTaskRuntimePhaseWorkflowDefinition.MONITOR_FIX_COMMIT_LOOP_ID,
        null,
      )
    return monitorEdges.size == expectedMonitorEdges.size &&
      monitorEdges.toSet() == expectedMonitorEdges.toSet() &&
      publicationEdges.singleOrNull() == expectedPublicationEdge
  }
}
