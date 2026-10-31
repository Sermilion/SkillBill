package skillbill.engine.goalrunner.planning.hydration

import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationResult
import skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict

public sealed interface GoalChildPlanningHydrateResult {
  public data class Hydrated(
    public val result: GoalChildPlanningHydrationResult,
  ) : GoalChildPlanningHydrateResult

  public data class Conflicted(
    public val conflict: GoalPlanningPreparationConflict,
  ) : GoalChildPlanningHydrateResult
}

internal sealed interface GoalChildPlanningHydrationOutcome {
  data class Hydrated(
    val hydration: GoalChildPlanningHydration,
  ) : GoalChildPlanningHydrationOutcome

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalChildPlanningHydrationOutcome
}
