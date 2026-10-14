package skillbill.infrastructure.sqlite.workflow.shared

import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.GoalPlanningStatusProjectionSql
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.translateSqlFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.translateSqlFailureResult
import skillbill.infrastructure.sqlite.workflow.goalrunner.shared.GoalSharedPreplanSql
import skillbill.ports.goalrunner.SharedGoalPreplanRepository
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult

internal class SharedGoalPreplanStore(
  private val statusProjection: GoalPlanningStatusProjectionSql,
  private val sharedPreplan: GoalSharedPreplanSql,
) : SharedGoalPreplanRepository {
  override fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean =
    translateSqlFailure(
      parentGoalWorkflowId,
      0,
    ) {
      statusProjection.hasPreparedSharedPreplan(parentGoalWorkflowId)
    }

  override fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> =
    translateSqlFailure(
      parentGoalWorkflowId,
      0,
    ) {
      statusProjection.listPreparedPlanSubtaskIds(parentGoalWorkflowId)
    }

  override fun preparedPlanStatusCount(parentGoalWorkflowId: String): Int =
    translateSqlFailure(parentGoalWorkflowId, 0) { statusProjection.preparedPlanStatusCount(parentGoalWorkflowId) }

  override fun preparedPlanIdsForSubtasks(
    parentGoalWorkflowId: String,
    subtaskIds: Set<Int>,
  ): Set<Int> =
    translateSqlFailure(parentGoalWorkflowId, 0) {
      statusProjection.preparedPlanIdsForSubtasks(parentGoalWorkflowId, subtaskIds)
    }

  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanLookupResult =
    translateSqlFailureResult(
      expectedIdentity.parentGoalWorkflowId,
      0,
      SharedGoalPreplanLookupResult::Conflicted,
    ) {
      sharedPreplan.findSharedPreplan(expectedIdentity)
    }

  override fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? =
    translateSqlFailure(parentGoalWorkflowId, 0) {
      sharedPreplan.sharedPreplanPayloadSha256(parentGoalWorkflowId)
    }
}
