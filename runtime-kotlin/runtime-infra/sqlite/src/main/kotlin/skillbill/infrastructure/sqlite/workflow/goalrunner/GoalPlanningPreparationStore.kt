package skillbill.infrastructure.sqlite.workflow.goalrunner

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.GoalPlanningStatusProjectionSql
import skillbill.infrastructure.sqlite.workflow.goalrunner.shared.GoalSharedPreplanSql
import skillbill.infrastructure.sqlite.workflow.goalrunner.subtask.GoalSubtaskPlanSql
import skillbill.infrastructure.sqlite.workflow.goalrunner.subtask.GoalSubtaskPlanStore
import skillbill.infrastructure.sqlite.workflow.shared.SharedGoalPreplanStore
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalSubtaskPlanRepository
import skillbill.ports.goalrunner.SharedGoalPreplanRepository
import java.sql.Connection

internal class GoalPlanningPreparationStore(
  private val connection: Connection,
) : GoalPlanningPreparationRepository,
  SharedGoalPreplanRepository by SharedGoalPreplanStore(
    GoalPlanningStatusProjectionSql(connection),
    GoalSharedPreplanSql(connection),
  ),
  GoalSubtaskPlanRepository by GoalSubtaskPlanStore(
    GoalPlanningStatusProjectionSql(connection),
    GoalSubtaskPlanSql(connection),
  ) {
  private val sharedPreplan = GoalSharedPreplanSql(connection)
  private val subtaskPlan = GoalSubtaskPlanSql(connection)

  override fun deleteByGoal(parentGoalWorkflowId: String): Int {
    val plans = subtaskPlan.deleteAllByGoal(parentGoalWorkflowId)
    val shared = sharedPreplan.deleteAllByGoal(parentGoalWorkflowId)
    return plans + shared + deletePreparedByGoal(parentGoalWorkflowId)
  }

  private fun deletePreparedByGoal(parentGoalWorkflowId: String): Int =
    connection.prepareStatement(
      "DELETE FROM goal_planning_preparations WHERE parent_goal_workflow_id = ?",
    ).use { statement ->
      statement.bindAll(parentGoalWorkflowId)
      statement.executeUpdate()
    }
}
