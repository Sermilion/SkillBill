package skillbill.infrastructure.sqlite.workflow

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.sql.Connection
import java.sql.ResultSet

internal object WorkflowStateSqlReads

internal fun Connection.getWorkflowRow(
  tableName: String,
  workflowId: String,
): WorkflowStateRecord? =
  prepareStatement(
    """
    SELECT
      workflow_id,
      session_id,
      workflow_name,
      contract_version,
      workflow_status,
      current_step_id,
      steps_json,
      artifacts_json,
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
      finished_at
    FROM $tableName
    WHERE workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(workflowId)
    statement.executeQuery().use { resultSet ->
      if (!resultSet.next()) {
        return null
      }
      resultSet.toWorkflowStateRecord()
    }
  }

internal fun Connection.getFeatureTaskWorkflowRow(workflowId: String): WorkflowStateRecord? =
  prepareStatement(
    """
    SELECT
      workflow_id,
      session_id,
      workflow_name,
      mode,
      implementation_skill,
      contract_version,
      workflow_status,
      current_step_id,
      steps_json,
      artifacts_json,
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
      finished_at
    FROM feature_task_workflows
    WHERE workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(workflowId)
    statement.executeQuery().use { resultSet ->
      if (!resultSet.next()) {
        return null
      }
      resultSet.toFeatureTaskWorkflowStateRecord()
    }
  }

internal fun Connection.getWorkflowRows(
  tableName: String,
  workflowIds: Set<String>,
): Map<String, WorkflowStateRecord> {
  if (workflowIds.isEmpty()) {
    return emptyMap()
  }
  return prepareStatement(
    """
    SELECT
      workflow_id,
      session_id,
      workflow_name,
      contract_version,
      workflow_status,
      current_step_id,
      steps_json,
      artifacts_json,
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
      finished_at
    FROM $tableName
    WHERE workflow_id IN (${workflowIds.workflowSqlPlaceholders()})
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(workflowIds)
    statement.executeQuery().use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          val row = resultSet.toWorkflowStateRecord()
          put(row.workflowId, row)
        }
      }
    }
  }
}

internal fun Connection.getFeatureTaskWorkflowRows(
  mode: FeatureTaskWorkflowMode,
  workflowIds: Set<String>,
): Map<String, WorkflowStateRecord> {
  if (workflowIds.isEmpty()) {
    return emptyMap()
  }
  return prepareStatement(
    """
    SELECT
      workflow_id,
      session_id,
      workflow_name,
      mode,
      implementation_skill,
      contract_version,
      workflow_status,
      current_step_id,
      steps_json,
      artifacts_json,
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
      finished_at
    FROM feature_task_workflows
    WHERE mode = ? AND workflow_id IN (${workflowIds.workflowSqlPlaceholders()})
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(listOf(mode.wireValue) + workflowIds)
    statement.executeQuery().use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          val row = resultSet.toFeatureTaskWorkflowStateRecord()
          put(row.workflowId, row)
        }
      }
    }
  }
}

internal fun decodeWorkflowStringList(rawValue: String?): List<String> {
  if (rawValue.isNullOrBlank()) {
    return emptyList()
  }
  val parsed =
    try {
      JsonCodec.parseJsonArrayStrict(rawValue.trim())
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      throw InvalidWorkflowStateSchemaError("spec_input_types must be a JSON array")
    }
  return parsed.map { element ->
    element as? String ?: throw InvalidWorkflowStateSchemaError("spec_input_types entries must be strings")
  }
}

internal fun Set<String>.workflowSqlPlaceholders(): String = joinToString(",") { "?" }

internal fun Connection.listWorkflowRows(
  tableName: String,
  limit: Int,
): List<WorkflowStateRecord> {
  val normalizedLimit = limit.coerceAtLeast(0)
  return prepareStatement(
    """
    SELECT
      workflow_id,
      session_id,
      workflow_name,
      contract_version,
      workflow_status,
      current_step_id,
      steps_json,
      artifacts_json,
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
      finished_at
    FROM $tableName
    ORDER BY updated_at DESC, rowid DESC
    LIMIT ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(normalizedLimit)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(resultSet.toWorkflowStateRecord())
        }
      }
    }
  }
}

internal fun Connection.getFeatureTaskWorkflowRowAsMode(
  workflowId: String,
  mode: FeatureTaskWorkflowMode,
): WorkflowStateRecord? {
  val row = getFeatureTaskWorkflowRow(workflowId) ?: return null
  if (row.mode != mode) {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task workflow '$workflowId' is mode='${row.mode?.wireValue.orEmpty()}', not '${mode.wireValue}'.",
    )
  }
  return row
}

internal fun Connection.listFeatureTaskWorkflowRows(
  mode: FeatureTaskWorkflowMode,
  limit: Int,
): List<WorkflowStateRecord> {
  val normalizedLimit = limit.coerceAtLeast(0)
  return prepareStatement(
    """
    SELECT
      workflow_id,
      session_id,
      workflow_name,
      mode,
      implementation_skill,
      contract_version,
      workflow_status,
      current_step_id,
      steps_json,
      artifacts_json,
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
      finished_at
    FROM feature_task_workflows
    WHERE mode = ?
    ORDER BY updated_at DESC, rowid DESC
    LIMIT ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(mode.wireValue, normalizedLimit)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(resultSet.toFeatureTaskWorkflowStateRecord())
        }
      }
    }
  }
}

internal fun ResultSet.toWorkflowStateRecord(): WorkflowStateRecord =
  WorkflowStateRecord(
    workflowId = getString(SharedPayloadKeys.WORKFLOW_ID),
    sessionId = getString("session_id"),
    workflowName = getString("workflow_name"),
    contractVersion = getString(SharedPayloadKeys.CONTRACT_VERSION),
    workflowStatus = getString("workflow_status"),
    currentStepId = getString("current_step_id"),
    stepsJson = getString("steps_json"),
    artifactsJson = getString("artifacts_json"),
    issueKey = getString(SharedPayloadKeys.ISSUE_KEY),
    startedAt = getString("started_at"),
    updatedAt = getString("updated_at"),
    stateEnteredAt = getString("state_entered_at"),
    stateEnteredAtEstimated = getInt("state_entered_at_estimated") != 0,
    finishedAt = getString("finished_at"),
  )

internal fun ResultSet.toFeatureTaskWorkflowStateRecord(): WorkflowStateRecord {
  val workflowId = getString(SharedPayloadKeys.WORKFLOW_ID)
  val workflowName = getString("workflow_name")
  if (workflowName != "bill-feature-task") {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task workflow '$workflowId' must persist workflow_name='bill-feature-task'; found '$workflowName'.",
    )
  }
  val rawMode = getString("mode")
  val mode =
    FeatureTaskWorkflowMode.fromWireValue(rawMode)
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task workflow '$workflowId' has unknown mode '$rawMode'.",
      )
  return WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = getString("session_id"),
    workflowName = workflowName,
    contractVersion = getString(SharedPayloadKeys.CONTRACT_VERSION),
    workflowStatus = getString("workflow_status"),
    currentStepId = getString("current_step_id"),
    stepsJson = getString("steps_json"),
    artifactsJson = getString("artifacts_json"),
    issueKey = getString(SharedPayloadKeys.ISSUE_KEY),
    startedAt = getString("started_at"),
    updatedAt = getString("updated_at"),
    stateEnteredAt = getString("state_entered_at"),
    stateEnteredAtEstimated = getInt("state_entered_at_estimated") != 0,
    finishedAt = getString("finished_at"),
    mode = mode,
    implementationSkill = getString("implementation_skill"),
  )
}
