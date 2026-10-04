package skillbill.engine.goalrunner.planning.model

import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict

data class GoalPlanningPreparationProgress(
  val sharedPreplanPrepared: Boolean,
  val preparedPlanCount: Int,
  val expectedPlanCount: Int,
  val missingSubtaskIds: List<Int>,
) {
  val firstMissingSubtaskId: Int? get() = missingSubtaskIds.firstOrNull()
}

sealed interface GoalPlanningRecoveryProgress {
  data class Ready(val progress: GoalPlanningPreparationProgress) : GoalPlanningRecoveryProgress

  data class IncompletePlan(
    val parentGoalWorkflowId: String,
    val subtaskId: Int,
    val reason: String,
  ) : GoalPlanningRecoveryProgress

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalPlanningRecoveryProgress
}
