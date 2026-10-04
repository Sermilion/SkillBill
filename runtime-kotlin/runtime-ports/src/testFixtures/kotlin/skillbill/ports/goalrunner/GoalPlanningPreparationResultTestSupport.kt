package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalSubtaskPlanListResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult

fun SharedGoalPreplanLookupResult.foundCheckpoint() = when (this) {
  is SharedGoalPreplanLookupResult.Found -> checkpoint
  is SharedGoalPreplanLookupResult.Conflicted -> error("Unexpected shared-preplan conflict in test setup.")
}

fun GoalSubtaskPlanLookupResult.foundPlan() = when (this) {
  is GoalSubtaskPlanLookupResult.Found -> plan
  is GoalSubtaskPlanLookupResult.Conflicted -> error("Unexpected subtask-plan conflict in test setup.")
}

fun GoalSubtaskPlanListResult.foundPlans() = when (this) {
  is GoalSubtaskPlanListResult.Found -> plans
  is GoalSubtaskPlanListResult.Conflicted -> error("Unexpected subtask-plan list conflict in test setup.")
}
