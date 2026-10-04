package skillbill.infrastructure.sqlite.workflow.goalrunner.subtask

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.GoalPlanningStatusProjectionSql
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.translateSqlFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.translateSqlFailureResult
import skillbill.ports.goalrunner.GoalSubtaskPlanRepository
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationCountResult
import skillbill.ports.goalrunner.model.GoalPlanningPreparationWriteResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GoalSubtaskPlanListResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor

internal class GoalSubtaskPlanStore(
  private val statusProjection: GoalPlanningStatusProjectionSql,
  private val subtaskPlan: GoalSubtaskPlanSql,
) : GoalSubtaskPlanRepository {
  override fun boundedStatus(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot =
    translateSqlFailure(parentGoalWorkflowId, blockedSubtaskId ?: 0) {
      statusProjection.boundedStatus(
        parentGoalWorkflowId,
        orderedSubtaskIds,
        blockedSubtaskId,
        blockedReason,
      )
    }

  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): GoalPlanningPreparationWriteResult =
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, checkpoint.subtaskId) {
      subtaskPlan.checkpointSubtaskPlan(checkpoint)
    }

  override fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): GoalPlanningPreparationWriteResult =
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, checkpoint.subtaskId) {
      subtaskPlan.replaceSubtaskPlan(checkpoint)
    }

  override fun deleteSubtaskPlan(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): Int =
    translateSqlFailure(parentGoalWorkflowId, subtaskId) {
      subtaskPlan.deleteSubtaskPlan(parentGoalWorkflowId, subtaskId)
    }

  override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanLookupResult =
    translateSqlFailureResult(
      expectedIdentity.parentGoalWorkflowId,
      subtaskId,
      GoalSubtaskPlanLookupResult::Conflicted,
    ) {
      subtaskPlan.findSubtaskPlan(expectedIdentity, subtaskId, governedSubSpecPath)
    }

  override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): GoalSubtaskPlanListResult =
    translateSqlFailureResult(
      expectedIdentity.parentGoalWorkflowId,
      0,
      GoalSubtaskPlanListResult::Conflicted,
    ) {
      subtaskPlan.listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors)
    }

  override fun preparedPlanCount(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): GoalPlanningPreparationCountResult =
    when (val listed = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors)) {
      is GoalSubtaskPlanListResult.Found -> GoalPlanningPreparationCountResult.Applied(listed.plans.size)
      is GoalSubtaskPlanListResult.Conflicted -> GoalPlanningPreparationCountResult.Conflicted(listed.conflict)
    }
}
