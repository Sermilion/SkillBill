package skillbill.infrastructure.sqlite.worklist

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkBoardCandidateRow
import skillbill.ports.work.model.WorkGoalDependencyAnchor
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.ports.work.model.WorkGoalParentCandidate
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.work.model.WorkListListRead
import skillbill.ports.work.model.WorkListRejectedRow
import skillbill.ports.work.model.WorkStatusCandidatePage
import skillbill.ports.work.model.WorkStatusCandidateQuery
import skillbill.ports.work.model.WorkStoreObservation
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.time.parsePersistedInstantOrNull
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

internal class SQLiteWorkListRepository(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
) : WorkListRepository {
  override fun boardCandidates(query: WorkBoardCandidateQuery): List<WorkBoardCandidateRow> =
    readWorkBoardCandidates(connection, query)

  override fun goalSubtaskPage(
    workflowId: String,
    offset: Int,
    limit: Int,
    completedOnly: Boolean,
    afterSubtaskId: Int?,
  ): WorkGoalSubtaskPage? {
    val selection = if (completedOnly) WorkGoalSubtaskSelection.Completed else WorkGoalSubtaskSelection.All
    return goalSubtasks.page(workflowId, offset, limit, selection, afterSubtaskId)
  }

  private val goalSubtasks = WorkGoalSubtaskStore(connection)

  private val goalDependencies = WorkGoalDependencyStore(connection)

  override fun observation(): WorkStoreObservation =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT store_id, (SELECT sum(revision) FROM ${DesktopReadSchema.REVISIONS_TABLE} " +
          "WHERE topic IN ($OBSERVED_TOPICS)) AS revision " +
          "FROM ${DesktopReadSchema.IDENTITY_TABLE} WHERE id = 1",
      ).use { rows ->
        if (!rows.next()) throw invalidWorkListRowError("Work store identity is missing.")
        WorkStoreObservation(rows.getString("store_id"), rows.getLong("revision"))
      }
    }

  override fun goalSubtasksByIds(
    workflowId: String,
    subtaskIds: Set<Int>,
  ): List<DecompositionSubtask> =
    if (subtaskIds.isEmpty()) {
      emptyList()
    } else {
      goalSubtasks.page(
        workflowId,
        0,
        subtaskIds.size,
        WorkGoalSubtaskSelection.Ids(subtaskIds),
      )?.manifest?.subtasks.orEmpty()
    }

  override fun goalParentCandidatePage(
    issueKey: String,
    repositoryIdentity: String?,
    offset: Int,
    limit: Int,
  ): List<WorkGoalParentCandidate> =
    readWorkGoalParentCandidatePage(connection, issueKey, repositoryIdentity, offset, limit)

  override fun goalDependencyPage(
    workflowId: String,
    offset: Int,
    limit: Int,
    subtaskId: Int?,
    after: WorkGoalDependencyAnchor?,
  ): WorkGoalDependencyPage = goalDependencies.page(workflowId, offset, limit, subtaskId, after)

  override fun statusCandidates(query: WorkStatusCandidateQuery): WorkStatusCandidatePage =
    readWorkStatusCandidates(connection, query)

  override fun list(limit: Int?): WorkListListRead {
    require(limit == null || limit > 0) { "Work-list limit must be positive." }
    return connection.prepareStatement(listQuery()).use { statement ->
      statement.executeQuery().use { resultSet ->
        val items = mutableListOf<WorkItem>()
        val rejected = mutableListOf<WorkListRejectedRow>()
        var diagnosticsEmitted = 0
        var diagnosticsTruncated = false
        while (resultSet.next()) {
          val parsed = resultSet.tryWorkItemOrReject()
          if (parsed.item != null) {
            items += parsed.item
            if (limit != null && items.size >= limit) break
          } else if (parsed.rejected != null) {
            rejected += parsed.rejected
            if (diagnosticsEmitted < MAX_LIST_ROW_DIAGNOSTICS) {
              emitListRowSkipDiagnostic(parsed.rejected)
              diagnosticsEmitted++
            } else if (!diagnosticsTruncated) {
              diagnostics.warning(
                "seam=work_list value_expected=readable_work_list_row value_used=truncated " +
                  "skipped_diagnostics_cap=$MAX_LIST_ROW_DIAGNOSTICS",
              )
              diagnosticsTruncated = true
            }
          }
        }
        WorkListListRead(items, rejected)
      }
    }
  }

  private fun emitListRowSkipDiagnostic(rejected: WorkListRejectedRow) {
    diagnostics.warning(
      "seam=work_list value_expected=readable_work_list_row value_used=skipped " +
        "workflow_id=${rejected.workflowId} error=${rejected.detail}",
    )
  }

  override fun findByWorkflowId(workflowId: String): List<WorkItem> =
    connection.prepareStatement(query("WHERE workflow_id = ?")).use { statement ->
      statement.bindAll(workflowId, MAX_EXACT_WORKFLOW_MATCHES)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) add(rows.toWorkItem())
        }
      }
    }

  override fun repositoryIdentityForIssue(
    normalizedIssueKey: String,
    excludedWorkflowId: String,
  ): IssueRepositoryIdentityLookup {
    val identities =
      connection.prepareStatement(
        ISSUE_IDENTITY_SQL,
      ).use { statement ->
        statement.bindAll(
          normalizedIssueKey,
          excludedWorkflowId,
          normalizedIssueKey,
          excludedWorkflowId,
          normalizedIssueKey,
          excludedWorkflowId,
          normalizedIssueKey,
          excludedWorkflowId,
          normalizedIssueKey,
          excludedWorkflowId,
          normalizedIssueKey,
          excludedWorkflowId,
        )
        statement.executeQuery().use { rows ->
          buildList {
            while (rows.next()) add(rows.getString("repository_identity"))
          }
        }
      }
    return when (identities.size) {
      0 -> IssueRepositoryIdentityLookup.Missing
      1 -> IssueRepositoryIdentityLookup.Unique(identities.single())
      else -> IssueRepositoryIdentityLookup.Ambiguous(identities)
    }
  }

  override fun repositoryIdentityForGoalChildren(parentWorkflowId: String): IssueRepositoryIdentityLookup =
    readWorkGoalChildRepositoryIdentity(connection, parentWorkflowId)
}

private fun listQuery(predicate: String = ""): String =
  """
  SELECT issue_key, workflow_kind, workflow_id, started_at, current_state,
         state_entered_at, state_entered_at_estimated
  FROM ($WORK_ITEM_UNION_SQL)
  $predicate
  ORDER BY unixepoch(started_at) DESC, ${fractionalSecondsSql("started_at")} DESC, workflow_id DESC
  """.trimIndent()

internal const val WORK_ITEM_UNION_SQL: String =
  """
    SELECT issue_key,
           CASE mode
             WHEN 'prose' THEN 'feature-task-prose'
             WHEN 'runtime' THEN 'feature-task-runtime'
             ELSE mode
           END AS workflow_kind,
           workflow_id,
           started_at,
           workflow_status AS current_state,
           state_entered_at,
           state_entered_at_estimated
    FROM feature_task_workflows
    UNION ALL
    SELECT issue_key,
           'feature-verify' AS workflow_kind,
           workflow_id,
           started_at,
           workflow_status AS current_state,
           state_entered_at,
           state_entered_at_estimated
    FROM feature_verify_workflows
    UNION ALL
    SELECT issue_key,
           'feature-goal' AS workflow_kind,
           parent_workflow_id AS workflow_id,
           first_started_at AS started_at,
           status AS current_state,
           state_entered_at,
           state_entered_at_estimated
    FROM goal_issue_progress
  """

private fun query(predicate: String = ""): String = "${listQuery(predicate)}\nLIMIT ?"

internal fun fractionalSecondsSql(columnName: String): String =
  """
  CASE
    WHEN instr($columnName, '.') = 0 THEN 0
    ELSE CAST('0.' || substr(
      substr($columnName, instr($columnName, '.') + 1),
      1,
      CASE
        WHEN instr(substr($columnName, instr($columnName, '.') + 1), 'Z') > 0 THEN
          instr(substr($columnName, instr($columnName, '.') + 1), 'Z') - 1
        WHEN instr(substr($columnName, instr($columnName, '.') + 1), '+') > 0 THEN
          instr(substr($columnName, instr($columnName, '.') + 1), '+') - 1
        WHEN instr(substr($columnName, instr($columnName, '.') + 1), '-') > 0 THEN
          instr(substr($columnName, instr($columnName, '.') + 1), '-') - 1
        ELSE length(substr($columnName, instr($columnName, '.') + 1))
      END
    ) AS REAL)
  END
  """.trimIndent()

private data class ParsedWorkListRow(
  val item: WorkItem?,
  val rejected: WorkListRejectedRow?,
)

private fun ResultSet.tryWorkItemOrReject(): ParsedWorkListRow =
  try {
    ParsedWorkListRow(toWorkItem(), null)
  } catch (error: SkillBillRuntimeException) {
    if (error.code == WorkflowFailureCode.INVALID_WORK_LIST_ROW) {
      val workflowId = getString(SharedPayloadKeys.WORKFLOW_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: "<unknown>"
      ParsedWorkListRow(null, WorkListRejectedRow(workflowId, error.message.orEmpty()))
    } else {
      throw error
    }
  }

internal fun ResultSet.toWorkItem(): WorkItem {
  val workflowId = required("workflow_id")
  val kindValue = required("workflow_kind")
  val kind =
    WorkItemKind.entries.firstOrNull { it.wireValue == kindValue }
      ?: invalid(workflowId, "unknown workflow kind '$kindValue'")
  val estimatedValue =
    getObject("state_entered_at_estimated")
      ?: invalid(workflowId, "missing state_entered_at_estimated")
  val estimated =
    when ((estimatedValue as? Number)?.toInt()) {
      0 -> false
      1 -> true
      else -> invalid(workflowId, "invalid state_entered_at_estimated '$estimatedValue'")
    }
  return WorkItem(
    issueKey = getString(SharedPayloadKeys.ISSUE_KEY)?.trim()?.takeIf(String::isNotEmpty),
    workflowKind = kind,
    workflowId = workflowId,
    startedAt = parseInstant(required("started_at"), workflowId, "started_at"),
    currentState =
      required("current_state").also { state ->
        if (state !in validWorkStates) invalid(workflowId, "unknown current state '$state'")
      },
    stateEnteredAt = parseInstant(required("state_entered_at"), workflowId, "state_entered_at"),
    stateEnteredAtEstimated = estimated,
  )
}

private val LEGACY_FEATURE_TASK_PROSE_WORKFLOW_STATUSES: Set<String> =
  setOf(
    WorkflowStatus.PENDING.wireValue,
    WorkflowStatus.RUNNING.wireValue,
    WorkflowStatus.COMPLETED.wireValue,
    WorkflowStatus.FAILED.wireValue,
    WorkflowStatus.ABANDONED.wireValue,
    WorkflowStatus.BLOCKED.wireValue,
    WorkflowStatus.PAUSED.wireValue,
  )

private val validWorkStates: Set<String> =
  LEGACY_FEATURE_TASK_PROSE_WORKFLOW_STATUSES +
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.workflowStatuses +
    FeatureVerifyWorkflowDefinition.definition.workflowStatuses

internal fun ResultSet.required(column: String): String {
  val value = getString(column) ?: invalid(getString(SharedPayloadKeys.WORKFLOW_ID).orEmpty(), "missing $column")
  if (value.isBlank()) invalid(getString(SharedPayloadKeys.WORKFLOW_ID).orEmpty(), "missing $column")
  if (value != value.trim()) invalid(getString(SharedPayloadKeys.WORKFLOW_ID).orEmpty(), "invalid $column '$value'")
  return value
}

internal fun parseInstant(
  value: String,
  workflowId: String,
  column: String,
): Instant = parsePersistedInstantOrNull(value) ?: invalid(workflowId, "invalid $column '$value'")

private fun invalid(
  workflowId: String,
  detail: String,
  cause: Throwable? = null,
): Nothing {
  val label = if (workflowId.isBlank()) "<unknown>" else workflowId
  throw invalidWorkListRowError("Work-list row '$label' $detail.", cause)
}

private const val MAX_EXACT_WORKFLOW_MATCHES = 3
private const val MAX_LIST_ROW_DIAGNOSTICS = 32

private val OBSERVED_TOPICS = DesktopReadSchema.TOPICS.joinToString { topic -> "'$topic'" }

private val ISSUE_IDENTITY_SQL =
"""
WITH identity_evidence(repository_identity) AS (
  SELECT identities.repository_identity
  FROM feature_task_execution_identities AS identities
  WHERE identities.normalized_issue_key = ? AND identities.workflow_id <> ?
  UNION ALL
  SELECT executions.repository_identity
  FROM ide_status_workflow_execution AS executions
  JOIN feature_task_workflows AS workflows ON workflows.workflow_id = executions.workflow_id
  WHERE UPPER(TRIM(COALESCE(NULLIF(executions.issue_key, ''), workflows.issue_key))) = ?
    AND executions.workflow_id <> ?
    AND NOT EXISTS (
      SELECT 1 FROM feature_task_execution_identities AS identities
      WHERE identities.workflow_id = executions.workflow_id
    )
  UNION ALL
  SELECT CASE WHEN json_valid(controls.control_state_json)
      AND json_type(controls.control_state_json, '$.repository_identity') = 'text'
    THEN json_extract(controls.control_state_json, '$.repository_identity') END
  FROM feature_task_workflows AS goals
  JOIN goal_runner_controls AS controls ON controls.parent_workflow_id = goals.workflow_id
  WHERE UPPER(TRIM(goals.issue_key)) = ? AND goals.workflow_id <> ?
  UNION ALL
  SELECT executions.repository_identity
  FROM ide_status_workflow_execution AS executions
  JOIN feature_task_workflows AS goals ON goals.workflow_id = executions.workflow_id
  WHERE UPPER(TRIM(COALESCE(NULLIF(executions.issue_key, ''), goals.issue_key))) = ?
    AND executions.workflow_id <> ?
    AND NOT EXISTS (
      SELECT 1 FROM goal_runner_controls AS controls
      WHERE controls.parent_workflow_id = goals.workflow_id
        AND CASE WHEN json_valid(controls.control_state_json)
            AND json_type(controls.control_state_json, '$.repository_identity') = 'text'
          THEN json_extract(controls.control_state_json, '$.repository_identity') END IS NOT NULL
    )
  UNION ALL
  SELECT preparations.repository_identity
  FROM goal_planning_preparations AS preparations
  JOIN feature_task_workflows AS goals
    ON goals.workflow_id = preparations.parent_goal_workflow_id
  WHERE UPPER(TRIM(goals.issue_key)) = ? AND goals.workflow_id <> ?
    AND NOT EXISTS (
      SELECT 1 FROM goal_runner_controls AS controls
      WHERE controls.parent_workflow_id = goals.workflow_id
        AND CASE WHEN json_valid(controls.control_state_json)
            AND json_type(controls.control_state_json, '$.repository_identity') = 'text'
          THEN json_extract(controls.control_state_json, '$.repository_identity') END IS NOT NULL
    )
    AND NOT EXISTS (
      SELECT 1 FROM ide_status_workflow_execution AS executions
      WHERE executions.workflow_id = goals.workflow_id
    )
  UNION ALL
  SELECT plans.repository_identity
  FROM goal_subtask_plans AS plans
  JOIN feature_task_workflows AS goals
    ON goals.workflow_id = plans.parent_goal_workflow_id
  WHERE UPPER(TRIM(goals.issue_key)) = ? AND goals.workflow_id <> ?
    AND NOT EXISTS (
      SELECT 1 FROM goal_runner_controls AS controls
      WHERE controls.parent_workflow_id = goals.workflow_id
        AND CASE WHEN json_valid(controls.control_state_json)
            AND json_type(controls.control_state_json, '$.repository_identity') = 'text'
          THEN json_extract(controls.control_state_json, '$.repository_identity') END IS NOT NULL
    )
    AND NOT EXISTS (
      SELECT 1 FROM ide_status_workflow_execution AS executions
      WHERE executions.workflow_id = goals.workflow_id
    )
)
SELECT DISTINCT repository_identity
FROM identity_evidence
WHERE repository_identity IS NOT NULL AND TRIM(repository_identity) <> ''
ORDER BY repository_identity
LIMIT 2
""".trimIndent()
