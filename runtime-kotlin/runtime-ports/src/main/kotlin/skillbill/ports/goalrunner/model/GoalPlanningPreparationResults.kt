package skillbill.ports.goalrunner.model

data class GoalPlanningPreparationConflict(
  val workflowId: String,
  val subtaskId: Int,
  val reason: String,
  val cause: Throwable?,
)

sealed interface GoalPlanningPreparationWriteResult {
  data object Applied : GoalPlanningPreparationWriteResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalPlanningPreparationWriteResult
}

sealed interface SharedGoalPreplanLookupResult {
  data class Found(
    val checkpoint: SharedGoalPreplanCheckpoint?,
  ) : SharedGoalPreplanLookupResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : SharedGoalPreplanLookupResult
}

sealed interface GoalSubtaskPlanLookupResult {
  data class Found(
    val plan: GoalSubtaskPlanCheckpoint?,
  ) : GoalSubtaskPlanLookupResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalSubtaskPlanLookupResult
}

sealed interface GoalSubtaskPlanListResult {
  data class Found(
    val plans: List<GoalSubtaskPlanCheckpoint>,
  ) : GoalSubtaskPlanListResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalSubtaskPlanListResult
}

sealed interface GoalPlanningPreparationCountResult {
  data class Applied(
    val count: Int,
  ) : GoalPlanningPreparationCountResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalPlanningPreparationCountResult
}
