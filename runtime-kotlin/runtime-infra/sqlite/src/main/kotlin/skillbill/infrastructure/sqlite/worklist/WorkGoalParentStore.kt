package skillbill.infrastructure.sqlite.worklist

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WorkGoalParentCandidate
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.sql.Connection

internal fun readWorkGoalChildRepositoryIdentity(
  connection: Connection,
  parentWorkflowId: String,
): IssueRepositoryIdentityLookup {
  val continuation = "$.${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()}"
  val parentPath = "$continuation.${FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID}"
  return connection.prepareStatement(
    """
    SELECT DISTINCT identities.repository_identity
    FROM feature_task_workflows AS workflows
    JOIN feature_task_execution_identities AS identities ON identities.workflow_id = workflows.workflow_id
    WHERE identities.route_scope = 'goal_child'
      AND CASE WHEN json_valid(workflows.artifacts_json)
        THEN json_extract(workflows.artifacts_json, '$parentPath')
      END = ?
    ORDER BY identities.repository_identity LIMIT 2
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(parentWorkflowId)
    statement.executeQuery().use { rows ->
      val identities = buildList { while (rows.next()) add(rows.getString("repository_identity")) }
      when (identities.size) {
        0 -> IssueRepositoryIdentityLookup.Missing
        1 -> IssueRepositoryIdentityLookup.Unique(identities.single())
        else -> IssueRepositoryIdentityLookup.Ambiguous(identities)
      }
    }
  }
}

internal fun readWorkGoalParentCandidatePage(
  connection: Connection,
  issueKey: String,
  repositoryIdentity: String?,
  offset: Int,
  limit: Int,
): List<WorkGoalParentCandidate> {
  require(offset >= 0 && limit in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
  val manifest = "$.${DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()}"
  val continuation = "$.${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()}"
  return connection.prepareStatement(
    """
    SELECT workflow_id, json_extract(artifacts_json, '$manifest.${SharedPayloadKeys.STATUS}') AS manifest_status,
      EXISTS(SELECT 1 FROM json_each(artifacts_json, '$manifest.${DecompositionPlanningPayloadKeys.SUBTASKS}')
        WHERE json_extract(value, '$.${SharedPayloadKeys.STATUS}') NOT IN (?, ?)) AS unsettled
    FROM feature_task_workflows
    WHERE mode = ? AND json_type(artifacts_json, '$manifest') = 'object'
      AND json_type(artifacts_json, '$continuation') IS NULL
      AND json_extract(artifacts_json, '$manifest.${SharedPayloadKeys.ISSUE_KEY}') = ?
      AND (? IS NULL OR COALESCE(
        (SELECT repository_identity FROM goal_planning_preparations WHERE parent_goal_workflow_id = workflow_id LIMIT 1),
        (SELECT json_extract(control_state_json, '$.repository_identity') FROM goal_runner_controls
          WHERE parent_workflow_id = workflow_id),
        (SELECT repository_identity FROM feature_task_execution_identities AS identities
          WHERE identities.workflow_id = feature_task_workflows.workflow_id), ?
      ) = ?)
    ORDER BY updated_at DESC, rowid DESC LIMIT ? OFFSET ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      DecompositionStatus.COMPLETE.wireValue, DecompositionStatus.SKIPPED.wireValue,
      FeatureTaskWorkflowMode.RUNTIME.wireValue, issueKey, repositoryIdentity,
      repositoryIdentity, repositoryIdentity, limit, offset,
    )
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) add(
          WorkGoalParentCandidate(rows.getString(1), rows.getString(2), rows.getBoolean("unsettled")),
        )
      }
    }
  }
}
