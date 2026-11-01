package skillbill.engine.goalrunner.planning.source

import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome

val PREPARE_ALL_GOAL_PLANNING_SOURCE: GoalPlanningSource =
  GoalPlanningSource { _, _ -> GoalPlanningSweepOutcome.PreparedAll() }
