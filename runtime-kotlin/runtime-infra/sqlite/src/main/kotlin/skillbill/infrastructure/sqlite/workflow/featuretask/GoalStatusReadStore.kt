package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.contracts.workflow.identity.status.WorkStatusArtifactKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalAttemptLedgerStatusEntry
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.toFeatureTaskWorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.sql.Connection

internal class GoalStatusReadStore(private val connection: Connection) {
  private val artifactKeys =
    listOf(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label(),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME.label(),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE.label(),
      DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT.label(),
      DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY.label(),
      DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT.label(),
      DecompositionManifestPayloadKeys.COMMIT_PUSH_RESULT,
      DecompositionManifestPayloadKeys.BLOCKED_REASON,
      WorkStatusArtifactKeys.PROGRESS_EVENT,
    )

  fun snapshot(workflowId: String): WorkflowStateSnapshot? =
    snapshots(
      "mode = ? AND workflow_id = ?",
      listOf(FeatureTaskWorkflowMode.RUNTIME.wireValue, workflowId),
      1,
      0,
    ).singleOrNull()

  fun childPage(
    issueKey: String,
    offset: Int,
    limit: Int,
    subtaskIds: Set<Int>?,
    parentWorkflowId: String? = null,
  ): List<WorkflowStateSnapshot> {
    require(offset >= 0 && limit in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
    require(subtaskIds == null || subtaskIds.size <= WorkPaginationLimits.MAX_PAGE_SIZE)
    if (subtaskIds?.isEmpty() == true) return emptyList()
    val selected = subtaskIds?.let { "AND goal_subtask_id IN (${it.joinToString { "?" }})" }.orEmpty()
    val parent = parentWorkflowId?.let { "AND parent_workflow_id = ?" }.orEmpty()
    return snapshots(
      "mode = ? AND goal_issue_key = ? $selected $parent",
      listOf(
        FeatureTaskWorkflowMode.RUNTIME.wireValue,
        issueKey,
      ) + subtaskIds.orEmpty() + listOfNotNull(parentWorkflowId),
      limit,
      offset,
    )
  }

  private fun snapshots(
    predicate: String,
    parameters: List<Any?>,
    limit: Int,
    offset: Int,
  ): List<WorkflowStateSnapshot> =
    connection.prepareStatement(
      """
      SELECT workflow_id, session_id, workflow_name, mode, implementation_skill, contract_version,
        workflow_status, current_step_id, steps_json, issue_key, started_at, updated_at, state_entered_at,
        state_entered_at_estimated, finished_at,
        (SELECT json_group_object(key, CASE WHEN type IN ('array', 'object') THEN json(value) ELSE value END)
         FROM json_each(artifacts_json) WHERE key IN (${artifactKeys.joinToString { "?" }})) AS artifacts_json
      FROM feature_task_workflows WHERE $predicate ORDER BY updated_at DESC, rowid DESC LIMIT ? OFFSET ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(artifactKeys + parameters + listOf(limit, offset))
      statement.executeQuery().use {
          rows ->
        buildList { while (rows.next()) add(rows.toFeatureTaskWorkflowStateRecord().toSnapshot()) }
      }
    }

  fun ledgerPage(
    workflowId: String,
    offset: Int,
    limit: Int,
  ): List<GoalAttemptLedgerStatusEntry> {
    require(offset >= 0 && limit in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
    val family = DurableWorkflowArtifactFamily.GOAL_ATTEMPT_LEDGER
    return connection.prepareStatement(
      """
      SELECT value FROM feature_task_workflows, json_each(artifacts_json, '$.${family.label()}')
      WHERE workflow_id = ? ORDER BY CAST(key AS INTEGER) LIMIT ? OFFSET ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId, limit, offset)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val wire =
              JsonCodec.parseValue(rows.getString(1))?.let(JsonCodec::anyToStringAnyMap)
                ?: throw invalidWorkflowStateSchemaError("Goal attempt ledger entry must be an object.")
            add(GoalAttemptLedgerStatusEntry.fromWire(FeatureTaskRuntimeWorkflowArtifactMap.from(wire)))
          }
        }
      }
    }
  }
}
