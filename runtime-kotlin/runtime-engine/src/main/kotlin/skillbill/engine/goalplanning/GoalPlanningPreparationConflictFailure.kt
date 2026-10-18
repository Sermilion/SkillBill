package skillbill.engine.goalplanning

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.incompatibleGoalPlanningPreparationRecoveryError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult

fun GoalPlanningPreparationConflict.toFailure(): SkillBillRuntimeException =
  incompatibleGoalPlanningPreparationRecoveryError(workflowId, subtaskId, reason, cause)

fun GoalSubtaskPlanLookupResult.planOrThrow(): GoalSubtaskPlanCheckpoint? =
  when (this) {
    is GoalSubtaskPlanLookupResult.Found -> plan
    is GoalSubtaskPlanLookupResult.Conflicted -> throw conflict.toFailure()
  }
