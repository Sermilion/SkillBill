package skillbill.engine.goalrunner.planning.model

data class GoalPlanningPreparationProgress(
  val sharedPreplanPrepared: Boolean,
  val preparedPlanCount: Int,
  val expectedPlanCount: Int,
  val missingSubtaskIds: List<Int>,
) {
  val firstMissingSubtaskId: Int? get() = missingSubtaskIds.firstOrNull()
}
