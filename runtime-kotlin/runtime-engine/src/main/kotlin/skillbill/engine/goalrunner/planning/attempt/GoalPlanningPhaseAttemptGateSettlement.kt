package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptRecordArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptScope
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.workflow.model.goalreview.GoalProgressOutcome

internal fun DefaultGoalPlanningSweep.settlePlanningProduction(
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction,
): GoalPlanningPhaseProduction? =
  when (production) {
    is GoalPlanningPhaseProduction.Stopped -> {
      recordPlanningAttempt(this, GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.FAILED))
      production
    }
    is GoalPlanningPhaseProduction.Captured -> {
      recordPlanningAttempt(this, GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.SUCCEEDED))
      production
    }
    is GoalPlanningPhaseProduction.EmptyProviderTurn -> {
      recordPlanningAttempt(this, GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.FAILED))
      recordEmptyProviderTurn(this, scope, production)
      backoffStop(this, scope)
    }
  }
