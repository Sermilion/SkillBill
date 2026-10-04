package skillbill.infrastructure.sqlite.worklist

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.time.parsePersistedInstant
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

internal class SQLiteWorkListRepository(
  private val connection: Connection,
) : WorkListRepository {
  override fun list(limit: Int?): List<WorkItem> {
    require(limit == null || limit > 0) { "Work-list limit must be positive." }
    return connection.prepareStatement(query()).use { statement ->
      statement.bindAll(limit ?: -1)
      statement.executeQuery().use { resultSet ->
        buildList<WorkItem> {
          while (resultSet.next()) {
            add(resultSet.toWorkItem())
          }
        }
      }
    }
  }
}

private fun query(): String =
  """
  SELECT issue_key, workflow_kind, workflow_id, started_at, current_state,
         state_entered_at, state_entered_at_estimated
  FROM (
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
  )
  ORDER BY unixepoch(started_at) DESC, ${fractionalSecondsSql("started_at")} DESC, workflow_id DESC
  LIMIT ?
  """.trimIndent()

private fun fractionalSecondsSql(columnName: String): String =
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

private fun ResultSet.toWorkItem(): WorkItem {
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
): Instant =
  try {
    parsePersistedInstant(value)
  } catch (error: IllegalArgumentException) {
    invalid(workflowId, "invalid $column '$value'", error)
  }

private fun invalid(
  workflowId: String,
  detail: String,
  cause: Throwable? = null,
): Nothing {
  val label = if (workflowId.isBlank()) "<unknown>" else workflowId
  throw invalidWorkListRowError("Work-list row '$label' $detail.", cause)
}
