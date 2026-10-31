package skillbill.engine.goalrunner.planning.model

public data class GoalChildPlanningHydration(
  public val currentStepId: String,
  public val stepUpdates: List<Map<String, Any?>>,
  public val artifacts: Map<String, Any?>,
)
