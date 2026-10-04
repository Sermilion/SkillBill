package skillbill.infrastructure.sqlite.workflow.featuretask

import java.sql.Connection
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.error.shellcontent.invalidFeatureTaskExecutionIdentitySchema
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.getFeatureTaskWorkflowRow
import skillbill.ports.workflow.FeatureTaskExecutionLookupRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.workflow.model.FeatureTaskExecutionIdentity

internal class FeatureTaskExecutionLookupStore(
  private val connection: Connection,
) : FeatureTaskExecutionLookupRepository {
  override fun claimFeatureTaskContinuation(
    workflowId: String,
    expectedUpdatedAt: String?,
  ): Boolean =
    connection.prepareStatement(
      """
      UPDATE feature_task_workflows
      SET workflow_status = 'running', updated_at = CURRENT_TIMESTAMP
      WHERE workflow_id = ?
        AND workflow_status NOT IN ('running', 'completed', 'failed', 'abandoned')
        AND ((updated_at IS NULL AND ? IS NULL) OR updated_at = ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId, expectedUpdatedAt, expectedUpdatedAt)
      statement.executeUpdate() == 1
    }

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_execution_identities (
        workflow_id, contract_version, normalized_issue_key, repository_identity,
        governed_spec_path, mode, route_scope
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id) DO NOTHING
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        identity.workflowId,
        identity.contractVersion,
        identity.normalizedIssueKey,
        identity.repositoryIdentity,
        identity.governedSpecPath,
        identity.mode.wireValue,
        identity.routeScope.wireValue,
      )
      statement.executeUpdate()
    }
    val persisted =
      connection.featureTaskIdentity(identity.workflowId)
        ?: throw invalidFeatureTaskExecutionIdentitySchema(identity.workflowId, "identity was not persisted")
    if (persisted != identity) {
      throw invalidFeatureTaskExecutionIdentitySchema(
        identity.workflowId,
        "immutable identity conflicts with the persisted record",
      )
    }
  }

  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    connection.featureTaskIdentity(workflowId)

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> =
    findFeatureTaskCandidates(
      normalizedIssueKey,
      repositoryIdentity,
      "standalone",
    )

  override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> =
    findFeatureTaskCandidates(
      normalizedIssueKey,
      repositoryIdentity,
      "goal_child",
    )

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int =
    connection.prepareStatement(
      """
      SELECT COUNT(*) AS child_count
      FROM feature_task_execution_identities
      WHERE normalized_issue_key = ? AND route_scope = 'goal_child'
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(normalizedIssueKey)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getInt("child_count") else 0 }
    }

  private fun findFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
    routeScope: String,
  ): List<FeatureTaskWorkflowCandidate> =
    connection.prepareStatement(
      """
      SELECT workflows.workflow_id
      FROM feature_task_workflows AS workflows
      LEFT JOIN feature_task_execution_identities AS identities
        ON identities.workflow_id = workflows.workflow_id
      WHERE (UPPER(workflows.issue_key) = ? OR identities.normalized_issue_key = ?)
        AND (
          (
            ? = 'standalone'
            AND identities.workflow_id IS NULL
            AND workflows.artifacts_json NOT LIKE '%"decomposition_runtime"%'
          )
          OR (identities.repository_identity = ? AND identities.route_scope = ?)
        )
      ORDER BY identities.created_at, workflows.workflow_id
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(normalizedIssueKey, normalizedIssueKey, routeScope, repositoryIdentity, routeScope)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val workflowId = rows.getString(SharedPayloadKeys.WORKFLOW_ID)
            val workflow =
              connection.getFeatureTaskWorkflowRow(workflowId)
                ?: throw InvalidWorkflowStateSchemaError(
                  "Feature-task identity '$workflowId' has no workflow row.",
                )
            add(FeatureTaskWorkflowCandidate(connection.featureTaskIdentity(workflowId), workflow))
          }
        }
      }
    }
}
