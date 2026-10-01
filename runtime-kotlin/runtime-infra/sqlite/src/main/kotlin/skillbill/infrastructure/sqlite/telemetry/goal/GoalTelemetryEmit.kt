package skillbill.infrastructure.sqlite.telemetry.goal

import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.telemetry.lifecycle.enqueueTelemetry
import skillbill.infrastructure.sqlite.telemetry.lifecycle.stringOrEmpty
import skillbill.infrastructure.sqlite.telemetry.redaction.telemetryRedactionSalt
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection
import java.sql.ResultSet

internal fun emitGoalStarted(
  connection: Connection,
  runtimeVersion: String,
  workflowId: String,
  level: String,
) {
  val row = goalRunSessionRow(connection, workflowId) ?: return
  if (row.stringOrEmpty("started_event_emitted_at").isNotBlank()) {
    return
  }
  val payload = goalStartedPayload(row, level, telemetryRedactionSalt(connection))
  enqueueTelemetry(connection, runtimeVersion, TelemetryOutboxEvent.GOAL_STARTED, payload)
  markGoalRunSessionEmitted(connection, "started_event_emitted_at", workflowId)
}

internal fun emitGoalFinished(
  connection: Connection,
  runtimeVersion: String,
  workflowId: String,
  level: String,
) {
  val row = goalRunSessionRow(connection, workflowId) ?: return
  if (row.stringOrEmpty("finished_event_emitted_at").isNotBlank()) {
    return
  }
  val payload = goalFinishedPayload(row, level, telemetryRedactionSalt(connection))
  enqueueTelemetry(connection, runtimeVersion, TelemetryOutboxEvent.GOAL_FINISHED, payload)
  markGoalRunSessionEmitted(connection, "finished_event_emitted_at", workflowId)
}

internal data class GoalIssueIdentity(val parentWorkflowId: String, val issueKey: String)

internal fun emitGoalIssueFinished(
  connection: Connection,
  runtimeVersion: String,
  goal: GoalIssueIdentity,
  level: String,
  diagnostics: RuntimeDiagnostics,
) {
  val row = goalIssueProgressRow(connection, goal.parentWorkflowId, goal.issueKey) ?: return
  if (row.stringOrEmpty("finished_event_emitted_at").isNotBlank()) {
    return
  }
  val payload =
    goalIssueFinishedPayload(
      row,
      level,
      telemetryRedactionSalt(connection),
      diagnostics,
    )
  enqueueTelemetry(connection, runtimeVersion, TelemetryOutboxEvent.GOAL_ISSUE_FINISHED, payload)
  markGoalIssueProgressEmitted(connection, goal.parentWorkflowId, goal.issueKey)
}

internal fun emitGoalSubtaskFinished(
  connection: Connection,
  runtimeVersion: String,
  record: GoalSubtaskFinishedRecord,
  level: String,
  diagnostics: RuntimeDiagnostics,
) {
  val row = goalSubtaskEventRow(connection, record.issueKey, record.subtaskId, record.workflowId) ?: return
  if (row.stringOrEmpty("subtask_event_emitted_at").isNotBlank()) {
    return
  }
  val payload =
    goalSubtaskFinishedPayload(
      row,
      level,
      telemetryRedactionSalt(connection),
      diagnostics,
    )
  enqueueTelemetry(connection, runtimeVersion, TelemetryOutboxEvent.GOAL_SUBTASK_FINISHED, payload)
  markGoalSubtaskEventEmitted(connection, record.issueKey, record.subtaskId, record.workflowId)
}

private fun goalRunSessionRow(
  connection: Connection,
  workflowId: String,
): Map<String, Any?>? =
  connection.prepareStatement("SELECT * FROM goal_run_sessions WHERE workflow_id = ?").use { statement ->
    statement.bindAll(workflowId)
    statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.toRowMap() else null }
  }

private fun goalSubtaskEventRow(
  connection: Connection,
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
): Map<String, Any?>? =
  connection.prepareStatement(
    "SELECT * FROM goal_subtask_events WHERE issue_key = ? AND subtask_id = ? AND workflow_id = ?",
  ).use { statement ->
    statement.bindAll(issueKey, subtaskId, workflowId)
    statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.toRowMap() else null }
  }

private fun goalIssueProgressRow(
  connection: Connection,
  parentWorkflowId: String,
  issueKey: String,
): Map<String, Any?>? =
  connection.prepareStatement(
    "SELECT * FROM goal_issue_progress WHERE parent_workflow_id = ? AND issue_key = ?",
  ).use { statement ->
    statement.bindAll(parentWorkflowId, issueKey)
    statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.toRowMap() else null }
  }

private fun markGoalRunSessionEmitted(
  connection: Connection,
  columnName: String,
  workflowId: String,
) {
  connection.prepareStatement(
    "UPDATE goal_run_sessions SET $columnName = CURRENT_TIMESTAMP WHERE workflow_id = ?",
  ).use { statement ->
    statement.bindAll(workflowId)
    statement.executeUpdate()
  }
}

private fun markGoalSubtaskEventEmitted(
  connection: Connection,
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
) {
  connection.prepareStatement(
    """
    UPDATE goal_subtask_events
    SET subtask_event_emitted_at = CURRENT_TIMESTAMP
    WHERE issue_key = ? AND subtask_id = ? AND workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(issueKey, subtaskId, workflowId)
    statement.executeUpdate()
  }
}

private fun markGoalIssueProgressEmitted(
  connection: Connection,
  parentWorkflowId: String,
  issueKey: String,
) {
  connection.prepareStatement(
    """
    UPDATE goal_issue_progress
    SET finished_event_emitted_at = CURRENT_TIMESTAMP
    WHERE parent_workflow_id = ? AND issue_key = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(parentWorkflowId, issueKey)
    statement.executeUpdate()
  }
}

private fun ResultSet.toRowMap(): Map<String, Any?> {
  val metadata = metaData
  return buildMap {
    for (index in 1..metadata.columnCount) {
      put(metadata.getColumnName(index), getObject(index))
    }
  }
}
