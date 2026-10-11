package skillbill.engine.goalrunner.planning.recovery

import skillbill.goalrunner.model.ExecutionLiveness

val IDLE_GOAL_PLANNING_REFRESH_LIVENESS: GoalPlanningRefreshLiveness =
  GoalPlanningRefreshLiveness { _ -> ExecutionLiveness.IDLE }
