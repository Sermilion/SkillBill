package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.planningStatusSnapshot
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.goalrunner.shared.INVALIDATED_SHARED_PREPLAN_PAYLOAD
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import java.sql.Connection

internal class GoalPlanningStatusProjectionSql(
  private val connection: Connection,
) {
  fun boundedStatus(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot {
    validateStatusRequest(parentGoalWorkflowId, orderedSubtaskIds, blockedSubtaskId, blockedReason)
    val shared = readSharedPreplanPrepared(parentGoalWorkflowId)
    val plannedIds = preparedPlanIds(parentGoalWorkflowId)
    validatePreparedPlanIds(parentGoalWorkflowId, orderedSubtaskIds, plannedIds)
    return planningStatusSnapshot(orderedSubtaskIds, plannedIds, shared, blockedSubtaskId, blockedReason)
  }

  fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean {
    requireParentGoalWorkflowId(parentGoalWorkflowId)
    return readSharedPreplanPrepared(parentGoalWorkflowId)
  }

  fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> {
    requireParentGoalWorkflowId(parentGoalWorkflowId)
    return preparedPlanIds(parentGoalWorkflowId)
  }

  private fun validateStatusRequest(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ) {
    if (orderedSubtaskIds.distinct().size != orderedSubtaskIds.size || orderedSubtaskIds.any { it < 1 }) {
      throw InvalidGoalPlanningPreparationSchemaError(
        parentGoalWorkflowId,
        "ordered_subtask_ids",
        "subtask ids must be unique positive integers",
      )
    }
    validateBlockedStatusRequest(parentGoalWorkflowId, orderedSubtaskIds, blockedSubtaskId, blockedReason)
  }

  private fun validateBlockedStatusRequest(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ) {
    if ((blockedSubtaskId == null) != (blockedReason == null)) {
      throw InvalidGoalPlanningPreparationSchemaError(
        parentGoalWorkflowId,
        "blocked_reason",
        "blocked subtask and reason must be supplied together",
      )
    }
    if (blockedSubtaskId != null && blockedSubtaskId !in orderedSubtaskIds) {
      throw InvalidGoalPlanningPreparationSchemaError(
        parentGoalWorkflowId,
        "blocked_subtask_id",
        "blocked subtask must be present in the governed ordering",
      )
    }
  }

  private fun readSharedPreplanPrepared(parentGoalWorkflowId: String): Boolean =
    connection.prepareStatement(
      "SELECT preparation_status, preplan_payload_json FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?",
    ).use { statement ->
      statement.bindAll(parentGoalWorkflowId)
      statement.executeQuery().use { result ->
        result.next() &&
          result.getString(1) == GoalPlanningPreparationState.PREPARED.wireValue &&
          result.getString(2) != INVALIDATED_SHARED_PREPLAN_PAYLOAD
      }
    }

  private fun preparedPlanIds(parentGoalWorkflowId: String): List<Int> =
    connection.prepareStatement(
      "SELECT subtask_id, preparation_status FROM goal_subtask_plans " +
        "WHERE parent_goal_workflow_id = ? ORDER BY manifest_order, subtask_id",
    ).use { statement ->
      statement.bindAll(parentGoalWorkflowId)
      statement.executeQuery().use { result ->
        buildList {
          while (result.next()) {
            if (result.getString("preparation_status") != GoalPlanningPreparationState.PREPARED.wireValue) {
              throw InvalidGoalPlanningPreparationSchemaError(
                parentGoalWorkflowId,
                "preparation_status",
                "plan checkpoint must be prepared",
              )
            }
            add(result.getInt("subtask_id"))
          }
        }
      }
    }

  private fun validatePreparedPlanIds(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    plannedIds: List<Int>,
  ) {
    if (plannedIds.any { it !in orderedSubtaskIds } || plannedIds.distinct().size != plannedIds.size) {
      throw InvalidGoalPlanningPreparationSchemaError(
        parentGoalWorkflowId,
        "subtask_id",
        "stored plan checkpoints must match the governed ordering",
      )
    }
  }
}
