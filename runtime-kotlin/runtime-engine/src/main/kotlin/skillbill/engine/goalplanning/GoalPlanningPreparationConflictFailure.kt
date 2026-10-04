package skillbill.engine.goalplanning

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.incompatibleGoalPlanningPreparationRecoveryError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.ports.goalrunner.model.GoalPlanningPreparationCountResult
import skillbill.ports.goalrunner.model.GoalPlanningPreparationWriteResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult

fun GoalPlanningPreparationConflict.toFailure(): SkillBillRuntimeException =
  incompatibleGoalPlanningPreparationRecoveryError(workflowId, subtaskId, reason, cause)

fun GoalPlanningPreparationWriteResult.appliedOrThrow() {
  if (this is GoalPlanningPreparationWriteResult.Conflicted) throw conflict.toFailure()
}

fun GoalPlanningPreparationCountResult.countOrThrow(): Int =
  when (this) {
    is GoalPlanningPreparationCountResult.Applied -> count
    is GoalPlanningPreparationCountResult.Conflicted -> throw conflict.toFailure()
  }

fun GoalSubtaskPlanLookupResult.planOrThrow(): GoalSubtaskPlanCheckpoint? =
  when (this) {
    is GoalSubtaskPlanLookupResult.Found -> plan
    is GoalSubtaskPlanLookupResult.Conflicted -> throw conflict.toFailure()
  }
