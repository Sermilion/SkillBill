package skillbill.engine.featuretask.model.execution

import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyDispatch
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyIdentity

internal class RecordedMonitorExecutionSemantics private constructor(
  val monitorSelection: ResolvedPhaseStrategyIdentity?,
  val dispatchOwners: Map<String, ResolvedPhaseStrategyDispatch>,
  val traversal: FeatureTaskRuntimeTransitionDeclaration,
) {
  companion object {
    fun from(plan: ResolvedPhaseExecutionPlan): RecordedMonitorExecutionSemantics {
      val selection = plan.selectedStrategies.singleOrNull { it.slot == PhaseSlot.MONITOR }
      val monitorSteps = (selection?.steps.orEmpty() + PhaseSlot.COMMIT_PUSH.steps).distinct()
      val owners =
        monitorSteps.mapNotNull { step ->
          plan.dispatchStrategyByStep[step]?.let { step to it }
        }.toMap()
      return RecordedMonitorExecutionSemantics(selection, owners, plan.traversal)
    }
  }
}
