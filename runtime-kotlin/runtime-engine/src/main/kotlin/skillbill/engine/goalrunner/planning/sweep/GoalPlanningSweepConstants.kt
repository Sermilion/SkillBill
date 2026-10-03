package skillbill.engine.goalrunner.planning.sweep

import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object GoalPlanningSweepConstants {
  const val PHASE_PREPLAN: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
  const val PHASE_PLAN: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
  const val SHARED_CONTEXT_FIELD = "_goal_planning_shared_context"
  const val EMPTY_PLANNING_HARVEST_RULE = "empty-planning-harvest"
}
