package skillbill.infrastructure.sqlite.workflow.goalrunner.child

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.workflow.GoalChildWorkflowStateRepository
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.workflow.model.WorkflowStatus
import java.sql.Connection

internal class GoalChildWorkflowStore(
  private val connection: Connection,
) : GoalChildWorkflowStateRepository {
  override fun listGoalChildWorkflowIdsByParent(parentWorkflowId: String): List<String> =
    connection.prepareStatement(
      """
      SELECT workflows.workflow_id
      FROM feature_task_workflows AS workflows
      JOIN feature_task_execution_identities AS identities
        ON identities.workflow_id = workflows.workflow_id
      WHERE identities.route_scope = 'goal_child'
        AND CASE WHEN json_valid(workflows.artifacts_json)
          THEN json_extract(workflows.artifacts_json, '$.goal_continuation.parent_workflow_id') END = ?
      ORDER BY workflows.workflow_id
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(parentWorkflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(rows.getString("workflow_id"))
          }
        }
      }
    }

  override fun deleteGoalChildWorkflowsByParent(parentWorkflowId: String): Int =
    connection.prepareStatement(
      """
      DELETE FROM feature_task_workflows
      WHERE workflow_id IN (
        SELECT workflows.workflow_id
        FROM feature_task_workflows AS workflows
        JOIN feature_task_execution_identities AS identities
          ON identities.workflow_id = workflows.workflow_id
        WHERE identities.route_scope = 'goal_child'
          AND CASE WHEN json_valid(workflows.artifacts_json)
          THEN json_extract(workflows.artifacts_json, '$.goal_continuation.parent_workflow_id') END = ?
      )
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(parentWorkflowId)
      statement.executeUpdate()
    }

  override fun deleteGoalChildWorkflow(
    parentWorkflowId: String,
    subtaskId: Int,
    workflowId: String,
    scope: GoalChildWorkflowDeletionScope,
  ): Int {
    val deletableStatuses = scope.deletableStatuses
    return connection.prepareStatement(
      """
      DELETE FROM feature_task_workflows
      WHERE workflow_id = ?
        AND workflow_status IN (${deletableStatuses.joinToString(", ") { "?" }})
        AND EXISTS (
          SELECT 1
          FROM feature_task_execution_identities AS identities
          WHERE identities.workflow_id = feature_task_workflows.workflow_id
            AND identities.route_scope = 'goal_child'
        )
        AND json_extract(artifacts_json, '$.goal_continuation.parent_workflow_id') = ?
        AND json_extract(artifacts_json, '$.goal_continuation.subtask_id') = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        listOf<Any?>(workflowId) +
          deletableStatuses.map(WorkflowStatus::wireValue) +
          listOf(parentWorkflowId, subtaskId),
      )
      statement.executeUpdate()
    }
  }
}
