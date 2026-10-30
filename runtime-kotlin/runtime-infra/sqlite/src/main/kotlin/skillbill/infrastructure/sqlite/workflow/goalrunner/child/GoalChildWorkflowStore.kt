package skillbill.infrastructure.sqlite.workflow.goalrunner.child

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.workflow.GoalChildWorkflowStateRepository
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
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
        AND workflows.parent_workflow_id = ?
      UNION
      SELECT children.workflow_id
      FROM feature_task_workflows AS parent
      JOIN json_each(
        parent.artifacts_json,
        '$.${DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()}.${DecompositionPlanningPayloadKeys.SUBTASKS}'
      ) AS subtask
      JOIN feature_task_workflows AS children
        ON children.workflow_id = json_extract(subtask.value, '$.${SharedPayloadKeys.WORKFLOW_ID}')
      JOIN feature_task_execution_identities AS identities
        ON identities.workflow_id = children.workflow_id
      WHERE parent.workflow_id = ?
        AND identities.route_scope = 'goal_child'
      ORDER BY 1
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(parentWorkflowId, parentWorkflowId)
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
          AND (
            workflows.parent_workflow_id = ?
            OR EXISTS (
              SELECT 1 FROM feature_task_workflows AS parent
              JOIN json_each(
                parent.artifacts_json,
                '$.${DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()}.${DecompositionPlanningPayloadKeys.SUBTASKS}'
              ) AS subtask
              WHERE parent.workflow_id = ?
                AND json_extract(subtask.value, '$.${SharedPayloadKeys.WORKFLOW_ID}') = workflows.workflow_id
            )
          )
      )
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(parentWorkflowId, parentWorkflowId)
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
        AND goal_subtask_id = ?
        AND (
          parent_workflow_id = ?
          OR (
            parent_workflow_id IS NULL
            AND EXISTS (
              SELECT 1 FROM feature_task_workflows AS parent
              JOIN json_each(
                parent.artifacts_json,
                '$.${DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()}.${DecompositionPlanningPayloadKeys.SUBTASKS}'
              ) AS subtask
              WHERE parent.workflow_id = ?
                AND json_extract(subtask.value, '$.${DecompositionPlanningPayloadKeys.ID}') = ?
                AND json_extract(subtask.value, '$.${SharedPayloadKeys.WORKFLOW_ID}') = feature_task_workflows.workflow_id
            )
          )
        )
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        listOf<Any?>(workflowId) +
          deletableStatuses.map(WorkflowStatus::wireValue) +
          listOf(subtaskId, parentWorkflowId, parentWorkflowId, subtaskId),
      )
      statement.executeUpdate()
    }
  }
}
