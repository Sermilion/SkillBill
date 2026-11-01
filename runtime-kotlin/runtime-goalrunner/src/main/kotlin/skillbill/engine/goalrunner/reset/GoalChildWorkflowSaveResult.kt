package skillbill.engine.goalrunner.reset

import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict

internal sealed interface GoalChildWorkflowSaveResult {
  data class Saved(
    val saved: SavedGoalChildWorkflow,
  ) : GoalChildWorkflowSaveResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalChildWorkflowSaveResult
}
