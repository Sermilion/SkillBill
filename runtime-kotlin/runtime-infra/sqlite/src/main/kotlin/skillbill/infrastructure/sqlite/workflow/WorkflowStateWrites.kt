package skillbill.infrastructure.sqlite.workflow
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.featuretask.requireUnchangedExecutionPlan
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
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

internal fun Connection.upsertWorkflowRow(
  tableName: String,
  row: WorkflowStateRecord,
  defaultContractVersion: String,
  clock: Clock,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  workflowSnapshotValidator.validate(row.toSnapshot(), row.workflowName)
  val transitionTimestamp = nextStateEnteredAtSql(tableName)
  val insertionTimestamp = row.startedAt.orInsertionTimestamp(clock)
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
      finished_at = CASE
        WHEN excluded.workflow_status IN ($terminalWorkflowStatusSqlValues)
          THEN COALESCE(NULLIF(excluded.finished_at, ''), CURRENT_TIMESTAMP)
        ELSE NULL
      END
    """.trimIndent(),
  ).use { statement ->
    statement.bindWorkflowRow(row, defaultContractVersion, insertionTimestamp)
    statement.executeUpdate()
  }
}

internal fun Connection.upsertFeatureTaskWorkflowRow(
  row: WorkflowStateRecord,
  request: FeatureTaskWorkflowUpsertRequest,
) {
  requireUnchangedExecutionPlan(getFeatureTaskWorkflowRow(row.workflowId), row)
  request.workflowSnapshotValidator.validate(row.toSnapshot(), row.workflowName)
  val transitionTimestamp = nextStateEnteredAtSql("feature_task_workflows")
  val insertionTimestamp = row.startedAt.orInsertionTimestamp(request.clock)
  prepareStatement(
    """
    INSERT INTO feature_task_workflows (
      workflow_id, session_id, workflow_name, contract_version, workflow_status, current_step_id,
      steps_json, artifacts_json, issue_key, started_at, updated_at, state_entered_at,
      state_entered_at_estimated, finished_at, mode, implementation_skill
    ) VALUES (?, ?, 'bill-feature-task', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
              CASE WHEN ? THEN COALESCE(NULLIF(?, ''), CURRENT_TIMESTAMP) ELSE NULL END, ?, ?)
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
      finished_at = CASE
        WHEN excluded.workflow_status IN ($terminalWorkflowStatusSqlValues)
          THEN COALESCE(NULLIF(excluded.finished_at, ''), CURRENT_TIMESTAMP)
        ELSE NULL
      END,
      mode = excluded.mode,
      implementation_skill = excluded.implementation_skill
    """.trimIndent(),
  ).use { statement ->
    statement.bindFeatureTaskWorkflowRow(
      row,
      request.mode,
      request.implementationSkill,
      request.defaultContractVersion,
      insertionTimestamp,
    )
    statement.executeUpdate()
  }
}

internal data class FeatureTaskWorkflowUpsertRequest(
  val mode: FeatureTaskWorkflowMode,
  val implementationSkill: String,
  val defaultContractVersion: String,
  val clock: Clock,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
)

internal fun Connection.terminalizeLegacyProseFeatureTaskWorkflowRow(row: WorkflowStateRecord) {
  val transitionTimestamp = nextStateEnteredAtSql("feature_task_workflows")
  prepareStatement(
    """
    UPDATE feature_task_workflows
    SET workflow_status = ?,
        artifacts_json = ?,
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
    parameters.text(row.currentStepId)
    parameters.text(row.workflowStatus)
    parameters.text(row.workflowStatus)
    parameters.text(row.finishedAt)
    parameters.text(row.workflowId)
    parameters.bind()
    val updated = statement.executeUpdate()
    if (updated != 1) {
      throw InvalidWorkflowStateSchemaError(
        "Legacy prose feature-task workflow '${row.workflowId}' was not terminalized " +
          "(missing row or mode is not prose).",
      )
    }
  }
}

private fun PreparedStatement.bindWorkflowRow(
  row: WorkflowStateRecord,
  defaultContractVersion: String,
  insertionTimestamp: String,
) {
  val parameters = SqlParameterBinder(this)
  parameters.text(row.workflowId)
  parameters.text(row.sessionId)
  parameters.text(row.workflowName)
  parameters.text(row.contractVersion.ifBlank { defaultContractVersion })
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
  mode: FeatureTaskWorkflowMode,
  implementationSkill: String,
  defaultContractVersion: String,
  insertionTimestamp: String,
) {
  val parameters = SqlParameterBinder(this)
  parameters.text(row.workflowId)
  parameters.text(row.sessionId)
  parameters.text(row.contractVersion.ifBlank { defaultContractVersion })
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
  parameters.text(mode.wireValue)
  parameters.text(implementationSkill)
  parameters.bind()
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

  fun bind() {
    statement.bindAll(values)
  }
}
