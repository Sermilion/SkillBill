package skillbill.engine.goalrunner.planning.hydration

import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationResult
import skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict

sealed interface GoalChildPlanningHydrateResult {
  data class Hydrated(
    val result: GoalChildPlanningHydrationResult,
  ) : GoalChildPlanningHydrateResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalChildPlanningHydrateResult
}

sealed interface GoalChildPlanningHydrationOutcome {
  data class Hydrated(
    val hydration: GoalChildPlanningHydration,
  ) : GoalChildPlanningHydrationOutcome

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalChildPlanningHydrationOutcome
}
