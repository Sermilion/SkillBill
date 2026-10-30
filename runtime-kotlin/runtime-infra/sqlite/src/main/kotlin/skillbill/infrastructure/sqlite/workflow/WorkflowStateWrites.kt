package skillbill.infrastructure.sqlite.workflow

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.error.shellcontent.terminalWorkflowWriteRefusedError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.isTerminal
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal const val FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION: String = "0.1"
internal const val FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION: String = "0.3"
internal const val MINIMUM_OWNER_TOKEN_LENGTH: Int = 16

private val terminalWorkflowStatusSqlValues: String =
  WorkflowStatus.terminalStatuses
    .joinToString(", ") { status -> "'${status.wireValue}'" }
private const val SQLITE_TIMESTAMP_NOW = "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"
private val sqliteInsertionTimestampFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

internal val FeatureTaskWorkflowMode.defaultImplementationSkill: String
  get() =
    when (this) {
      FeatureTaskWorkflowMode.PROSE -> "bill-feature"
      FeatureTaskWorkflowMode.RUNTIME -> "bill-feature"
    }

internal val FeatureTaskWorkflowMode.defaultContractVersion: String
  get() =
    when (this) {
      FeatureTaskWorkflowMode.PROSE -> FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION
      FeatureTaskWorkflowMode.RUNTIME -> FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION
    }

internal object WorkflowStateSqlWrites

internal data class GoalContinuationColumns(
  val parentWorkflowId: String?,
  val subtaskId: Long?,
  val issueKey: String?,
) {
  companion object {
    val NONE: GoalContinuationColumns = GoalContinuationColumns(null, null, null)
  }
}

internal fun DurableWorkflowArtifacts.goalContinuationColumns(): GoalContinuationColumns {
  val continuation =
    JsonCodec.anyToStringAnyMap(this[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()])
      ?: return GoalContinuationColumns.NONE
  return GoalContinuationColumns(
    parentWorkflowId =
      continuation[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID] as? String,
    subtaskId =
      when (val subtask = continuation[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUBTASK_ID]) {
        is Int -> subtask.toLong()
        is Long -> subtask
        else -> null
      },
    issueKey = continuation[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ISSUE_KEY] as? String,
  )
}

internal fun Connection.upsertWorkflowRow(
  tableName: String,
  row: WorkflowStateRecord,
  request: WorkflowRowUpsertRequest,
) {
  request.workflowSnapshotValidator.validate(row.toSnapshot(), row.workflowName)
  val transitionTimestamp = nextStateEnteredAtSql(tableName)
  val insertionTimestamp = row.startedAt.orInsertionTimestamp(request.clock)
  prepareStatement(
    """
    INSERT INTO $tableName (
      workflow_id, session_id, workflow_name, contract_version, workflow_status, current_step_id,
      steps_json, artifacts_json, issue_key, started_at, updated_at, state_entered_at,
      state_entered_at_estimated, finished_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
              CASE WHEN ? THEN COALESCE(NULLIF(?, ''), CURRENT_TIMESTAMP) ELSE NULL END)
    ON CONFLICT(workflow_id) DO UPDATE SET
      session_id = excluded.session_id,
      workflow_name = excluded.workflow_name,
      contract_version = excluded.contract_version,
      workflow_status = excluded.workflow_status,
      current_step_id = excluded.current_step_id,
      steps_json = excluded.steps_json,
      artifacts_json = excluded.artifacts_json,
      issue_key = COALESCE(NULLIF(excluded.issue_key, ''), $tableName.issue_key),
      updated_at = CURRENT_TIMESTAMP,
      state_entered_at = CASE
        WHEN $tableName.workflow_status != excluded.workflow_status THEN $transitionTimestamp
        ELSE $tableName.state_entered_at
      END,
      state_entered_at_estimated = CASE
        WHEN $tableName.workflow_status != excluded.workflow_status THEN 0
        ELSE $tableName.state_entered_at_estimated
      END,
      ${conflictFinishedAtSql(tableName, request.acceptedSnapshotMutation)}
    ${ordinaryTerminalConflictPredicate(tableName, request.acceptedSnapshotMutation)}
    """.trimIndent(),
  ).use { statement ->
    statement.bindWorkflowRow(row, request.defaultContractVersion, insertionTimestamp)
    if (statement.executeUpdate() != 1) {
      throw terminalWorkflowWriteRefusedError(row.workflowId)
    }
  }
}

internal fun Connection.upsertFeatureTaskWorkflowRow(
  row: WorkflowStateRecord,
  request: FeatureTaskWorkflowUpsertRequest,
) {
  requireUnchangedExecutionPlan(getFeatureTaskWorkflowRow(row.workflowId), row)
  val snapshot = row.toSnapshot()
  request.workflowSnapshotValidator.validate(snapshot, row.workflowName)
  val goalColumns = snapshot.artifacts.goalContinuationColumns()
  val transitionTimestamp = nextStateEnteredAtSql("feature_task_workflows")
  val insertionTimestamp = row.startedAt.orInsertionTimestamp(request.clock)
  prepareStatement(
    """
    INSERT INTO feature_task_workflows (
      workflow_id, session_id, workflow_name, contract_version, workflow_status, current_step_id,
      steps_json, artifacts_json, issue_key, started_at, updated_at, state_entered_at,
      state_entered_at_estimated, finished_at, mode, implementation_skill,
      parent_workflow_id, goal_subtask_id, goal_issue_key
    ) VALUES (?, ?, 'bill-feature-task', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
              CASE WHEN ? THEN COALESCE(NULLIF(?, ''), CURRENT_TIMESTAMP) ELSE NULL END, ?, ?, ?, ?, ?)
    ON CONFLICT(workflow_id) DO UPDATE SET
      session_id = excluded.session_id,
      workflow_name = 'bill-feature-task',
      contract_version = excluded.contract_version,
      workflow_status = excluded.workflow_status,
      current_step_id = excluded.current_step_id,
      steps_json = excluded.steps_json,
      artifacts_json = excluded.artifacts_json,
      issue_key = COALESCE(NULLIF(excluded.issue_key, ''), feature_task_workflows.issue_key),
      updated_at = CURRENT_TIMESTAMP,
      state_entered_at = CASE
        WHEN feature_task_workflows.workflow_status != excluded.workflow_status THEN $transitionTimestamp
        ELSE feature_task_workflows.state_entered_at
      END,
      state_entered_at_estimated = CASE
        WHEN feature_task_workflows.workflow_status != excluded.workflow_status THEN 0
        ELSE feature_task_workflows.state_entered_at_estimated
      END,
      ${conflictFinishedAtSql("feature_task_workflows", request.acceptedSnapshotMutation)},
      mode = excluded.mode,
      implementation_skill = excluded.implementation_skill,
      parent_workflow_id = excluded.parent_workflow_id,
      goal_subtask_id = excluded.goal_subtask_id,
      goal_issue_key = excluded.goal_issue_key
    ${ordinaryTerminalConflictPredicate("feature_task_workflows", request.acceptedSnapshotMutation)}
    """.trimIndent(),
  ).use { statement ->
    statement.bindFeatureTaskWorkflowRow(row, request, insertionTimestamp, goalColumns)
    if (statement.executeUpdate() != 1) {
      throw terminalWorkflowWriteRefusedError(row.workflowId)
    }
  }
}

internal data class WorkflowRowUpsertRequest(
  val defaultContractVersion: String,
  val clock: Clock,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val acceptedSnapshotMutation: Boolean = false,
)

internal data class FeatureTaskWorkflowUpsertRequest(
  val mode: FeatureTaskWorkflowMode,
  val implementationSkill: String,
  val defaultContractVersion: String,
  val clock: Clock,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val acceptedSnapshotMutation: Boolean = false,
)

private fun conflictFinishedAtSql(
  tableName: String,
  acceptedSnapshotMutation: Boolean,
): String =
  if (acceptedSnapshotMutation) {
    """
    finished_at = CASE
      WHEN excluded.workflow_status IN ($terminalWorkflowStatusSqlValues)
        THEN COALESCE(NULLIF(excluded.finished_at, ''), $tableName.finished_at, CURRENT_TIMESTAMP)
      ELSE NULL
    END
    """.trimIndent()
  } else {
    """
    finished_at = CASE
      WHEN $tableName.workflow_status IN ($terminalWorkflowStatusSqlValues)
        THEN $tableName.finished_at
      WHEN excluded.workflow_status IN ($terminalWorkflowStatusSqlValues)
        THEN COALESCE(NULLIF(excluded.finished_at, ''), CURRENT_TIMESTAMP)
      ELSE NULL
    END
    """.trimIndent()
  }

private fun ordinaryTerminalConflictPredicate(
  tableName: String,
  acceptedSnapshotMutation: Boolean,
): String =
  if (acceptedSnapshotMutation) {
    ""
  } else {
    """
    WHERE $tableName.workflow_status NOT IN ($terminalWorkflowStatusSqlValues)
      OR (
        $tableName.workflow_status = excluded.workflow_status
        AND $tableName.current_step_id = excluded.current_step_id
        AND $tableName.steps_json = excluded.steps_json
        AND $tableName.artifacts_json = excluded.artifacts_json
        AND $tableName.finished_at IS excluded.finished_at
      )
    """.trimIndent()
  }

internal fun Connection.terminalizeLegacyProseFeatureTaskWorkflowRow(row: WorkflowStateRecord) {
  val goalColumns = row.artifactsWithoutStrictSnapshotDecode().goalContinuationColumns()
  val transitionTimestamp = nextStateEnteredAtSql("feature_task_workflows")
  prepareStatement(
    """
    UPDATE feature_task_workflows
    SET workflow_status = ?,
        artifacts_json = ?,
        parent_workflow_id = ?,
        goal_subtask_id = ?,
        goal_issue_key = ?,
        current_step_id = ?,
        updated_at = CURRENT_TIMESTAMP,
        state_entered_at = CASE
          WHEN feature_task_workflows.workflow_status != ? THEN $transitionTimestamp
          ELSE feature_task_workflows.state_entered_at
        END,
        state_entered_at_estimated = CASE
          WHEN feature_task_workflows.workflow_status != ? THEN 0
          ELSE feature_task_workflows.state_entered_at_estimated
        END,
        finished_at = COALESCE(NULLIF(?, ''), CURRENT_TIMESTAMP)
    WHERE workflow_id = ?
      AND (mode = 'prose' OR mode IS NULL)
    """.trimIndent(),
  ).use { statement ->
    val parameters = SqlParameterBinder(statement)
    parameters.text(row.workflowStatus)
    parameters.text(row.artifactsJson)
    parameters.goalContinuation(goalColumns)
    parameters.text(row.currentStepId)
    parameters.text(row.workflowStatus)
    parameters.text(row.workflowStatus)
    parameters.text(row.finishedAt)
    parameters.text(row.workflowId)
    parameters.bind()
    val updated = statement.executeUpdate()
    if (updated != 1) {
      throw invalidWorkflowStateSchemaError(
        "Legacy prose feature-task workflow '${row.workflowId}' was not terminalized " +
          "(missing row or mode is not prose).",
      )
    }
  }
}

private fun WorkflowStateRecord.artifactsWithoutStrictSnapshotDecode(): DurableWorkflowArtifacts =
  DurableWorkflowArtifacts.fromAny(JsonCodec.parseValue(artifactsJson))

private fun PreparedStatement.bindWorkflowRow(
  row: WorkflowStateRecord,
  defaultContractVersion: String,
  insertionTimestamp: String,
) {
  val parameters = SqlParameterBinder(this)
  parameters.text(row.workflowId)
  parameters.text(row.sessionId)
  parameters.text(row.workflowName)
  parameters.text(persistableWorkflowStateContractVersion(row.contractVersion.ifBlank { defaultContractVersion }))
  parameters.text(row.workflowStatus)
  parameters.text(row.currentStepId)
  parameters.text(row.stepsJson)
  parameters.text(row.artifactsJson)
  parameters.text(row.issueKey)
  parameters.text(insertionTimestamp)
  parameters.text(insertionTimestamp)
  parameters.text(insertionTimestamp)
  parameters.boolean(WorkflowStatus.fromWire(row.workflowStatus)?.isTerminal == true)
  parameters.text(row.finishedAt)
  parameters.bind()
}

private fun PreparedStatement.bindFeatureTaskWorkflowRow(
  row: WorkflowStateRecord,
  request: FeatureTaskWorkflowUpsertRequest,
  insertionTimestamp: String,
  goalColumns: GoalContinuationColumns,
) {
  val parameters = SqlParameterBinder(this)
  parameters.text(row.workflowId)
  parameters.text(row.sessionId)
  val storedVersion = row.contractVersion.ifBlank { request.defaultContractVersion }
  parameters.text(
    if (request.mode == FeatureTaskWorkflowMode.RUNTIME) {
      persistableWorkflowStateContractVersion(storedVersion)
    } else {
      storedVersion
    },
  )
  parameters.text(row.workflowStatus)
  parameters.text(row.currentStepId)
  parameters.text(row.stepsJson)
  parameters.text(row.artifactsJson)
  parameters.text(row.issueKey)
  parameters.text(insertionTimestamp)
  parameters.text(insertionTimestamp)
  parameters.text(insertionTimestamp)
  parameters.boolean(WorkflowStatus.fromWire(row.workflowStatus)?.isTerminal == true)
  parameters.text(row.finishedAt)
  parameters.text(request.mode.wireValue)
  parameters.text(request.implementationSkill)
  parameters.goalContinuation(goalColumns)
  parameters.bind()
}

private fun persistableWorkflowStateContractVersion(stored: String): String =
  if (stored in WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS) {
    WORKFLOW_STATE_CONTRACT_VERSION
  } else {
    stored
  }

private fun nextStateEnteredAtSql(tableName: String): String =
  """
  CASE
    WHEN julianday(NULLIF($tableName.state_entered_at, '')) IS NULL THEN $SQLITE_TIMESTAMP_NOW
    WHEN julianday($SQLITE_TIMESTAMP_NOW) > julianday($tableName.state_entered_at) THEN $SQLITE_TIMESTAMP_NOW
    ELSE strftime('%Y-%m-%dT%H:%M:%fZ', julianday($tableName.state_entered_at) + 0.001 / 86400.0)
  END
  """.trimIndent()

private fun String?.orInsertionTimestamp(clock: Clock): String =
  takeUnless { it.isNullOrBlank() } ?: sqliteInsertionTimestampFormatter.format(clock.instant())

private class SqlParameterBinder(
  private val statement: PreparedStatement,
) {
  private val values = mutableListOf<Any?>()

  fun text(value: String?) {
    values += value
  }

  fun boolean(value: Boolean) {
    values += value
  }

  fun goalContinuation(columns: GoalContinuationColumns) {
    values += columns.parentWorkflowId
    values += columns.subtaskId
    values += columns.issueKey
  }

  fun bind() {
    statement.bindAll(values)
  }
}
