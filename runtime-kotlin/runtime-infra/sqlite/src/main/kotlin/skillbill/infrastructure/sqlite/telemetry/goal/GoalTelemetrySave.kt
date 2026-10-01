package skillbill.infrastructure.sqlite.telemetry.goal

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.telemetry.lifecycle.listJson
import skillbill.infrastructure.sqlite.telemetry.lifecycle.toSqlInt
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection

private const val SQLITE_TIMESTAMP_NOW = "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"

internal enum class GoalStartedSaveOutcome { INSERTED, DUPLICATE }

internal enum class GoalFinishedSaveOutcome { FIRST_TERMINAL, DUPLICATE }

internal data class GoalIssueFinishedSaveOutcome(val persisted: Boolean, val suppressionReason: String? = null)

internal fun saveGoalStarted(
  connection: Connection,
  record: GoalStartedRecord,
): GoalStartedSaveOutcome {
  val inserted =
    connection.prepareStatement(
      """
      INSERT INTO goal_run_sessions (
        workflow_id, issue_key, feature_name, subtask_total, resumed, started_at, mode, parent_workflow_id
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id) DO NOTHING
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        record.workflowId,
        record.issueKey,
        record.featureName,
        record.subtaskTotal,
        record.resumed.toSqlInt(),
        record.startedAt,
        record.mode,
        record.parentWorkflowId,
      )
      statement.executeUpdate() > 0
    }
  return if (inserted) GoalStartedSaveOutcome.INSERTED else GoalStartedSaveOutcome.DUPLICATE
}

internal fun saveGoalFinished(
  connection: Connection,
  record: GoalFinishedRecord,
): GoalFinishedSaveOutcome {
  if (goalRunSessionExists(connection, record.workflowId)) {
    return if (updateGoalFinished(connection, record)) {
      GoalFinishedSaveOutcome.FIRST_TERMINAL
    } else {
      GoalFinishedSaveOutcome.DUPLICATE
    }
  } else {
    insertGoalFinished(connection, record)
    return GoalFinishedSaveOutcome.FIRST_TERMINAL
  }
}

private fun updateGoalFinished(
  connection: Connection,
  record: GoalFinishedRecord,
): Boolean =
  connection.prepareStatement(
    """
    UPDATE goal_run_sessions SET
      issue_key = ?,
      status = ?,
      started_at = ?,
      finished_at = ?,
      finished_duration_ms = ?,
      subtasks_complete = ?,
      subtasks_blocked = ?,
      subtasks_skipped = ?,
      mode = ?,
      stop_reason = ?,
      parent_workflow_id = COALESCE(?, parent_workflow_id)
    WHERE workflow_id = ? AND status IS NULL AND finished_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.issueKey,
      record.status,
      record.startedAt,
      record.finishedAt,
      record.durationMs,
      record.subtasksComplete,
      record.subtasksBlocked,
      record.subtasksSkipped,
      record.mode,
      record.stopReason,
      record.parentWorkflowId,
      record.workflowId,
    )
    statement.executeUpdate() > 0
  }

private fun insertGoalFinished(
  connection: Connection,
  record: GoalFinishedRecord,
) {
  connection.prepareStatement(
    """
    INSERT INTO goal_run_sessions (
      workflow_id, issue_key, started_at, status, finished_at,
      finished_duration_ms, subtasks_complete, subtasks_blocked, subtasks_skipped, mode, stop_reason,
      parent_workflow_id
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.workflowId,
      record.issueKey,
      record.startedAt,
      record.status,
      record.finishedAt,
      record.durationMs,
      record.subtasksComplete,
      record.subtasksBlocked,
      record.subtasksSkipped,
      record.mode,
      record.stopReason,
      record.parentWorkflowId,
    )
    statement.executeUpdate()
  }
}

internal fun recordGoalIssueSegmentStarted(
  connection: Connection,
  segment: GoalIssueSegmentStart,
) {
  connection.prepareStatement(
    """
    INSERT INTO goal_issue_progress (
      parent_workflow_id, issue_key, total_invocations, total_resumes, first_started_at,
      last_activity_at, latest_segment_workflow_id, mode, status,
      state_entered_at, state_entered_at_estimated
    ) VALUES (?, ?, 1, ?, ?, ?, ?, ?, 'running', ?, 0)
    ON CONFLICT(parent_workflow_id, issue_key) DO UPDATE SET
      total_invocations = goal_issue_progress.total_invocations + 1,
      total_resumes = goal_issue_progress.total_resumes + excluded.total_resumes,
      first_started_at = COALESCE(goal_issue_progress.first_started_at, excluded.first_started_at),
      last_activity_at = excluded.last_activity_at,
      latest_segment_workflow_id = excluded.latest_segment_workflow_id,
      mode = excluded.mode,
      status = 'running',
      state_entered_at = CASE
        WHEN COALESCE(goal_issue_progress.status, '') != 'running' THEN
          ${nextGoalStateEnteredAtSql("excluded.state_entered_at")}
        ELSE goal_issue_progress.state_entered_at
      END,
      state_entered_at_estimated = CASE
        WHEN COALESCE(goal_issue_progress.status, '') != 'running' THEN 0
        ELSE goal_issue_progress.state_entered_at_estimated
      END
    WHERE goal_issue_progress.finished_at IS NULL
      AND COALESCE(goal_issue_progress.status, '') NOT IN ('completed', 'failed', 'abandoned')
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      segment.parentWorkflowId,
      segment.issueKey,
      segment.resumed.toSqlInt(),
      segment.startedAt,
      segment.startedAt,
      segment.workflowId,
      segment.mode,
      segment.startedAt,
    )
    statement.executeUpdate()
  }
}

internal data class GoalIssueSegmentStart(
  internal val parentWorkflowId: String,
  internal val issueKey: String,
  internal val workflowId: String,
  internal val startedAt: String,
  internal val resumed: Boolean,
  internal val mode: String,
)

internal fun recordGoalIssueSegmentEnd(
  connection: Connection,
  parentWorkflowId: String,
  issueKey: String,
  workflowId: String,
  status: String,
) {
  val blocked = status != "paused"
  val blockedColumns =
    if (blocked) {
      """
      total_blocks = total_blocks + 1,
          last_blocked_at = CURRENT_TIMESTAMP,
          last_blocked_segment_workflow_id = ?,
      """.trimIndent()
    } else {
      ""
    }
  connection.prepareStatement(
    """
    UPDATE goal_issue_progress
    SET $blockedColumns
        status = ?,
        state_entered_at = CASE
          WHEN COALESCE(status, '') != ? THEN ${nextGoalStateEnteredAtSql(SQLITE_TIMESTAMP_NOW)}
          ELSE state_entered_at
        END,
        state_entered_at_estimated = CASE
          WHEN COALESCE(status, '') != ? THEN 0
          ELSE state_entered_at_estimated
        END,
        last_activity_at = CURRENT_TIMESTAMP
    WHERE parent_workflow_id = ?
      AND issue_key = ?
      AND finished_at IS NULL
      AND COALESCE(status, '') NOT IN ('completed', 'failed', 'abandoned')
    """.trimIndent(),
  ).use { statement ->
    val tail = listOf(status, status, status, parentWorkflowId, issueKey)
    val params = if (blocked) listOf(workflowId) + tail else tail
    statement.bindAll(params)
    statement.executeUpdate()
  }
}

internal fun saveGoalIssueFinished(
  connection: Connection,
  record: GoalIssueFinishedRecord,
  diagnostics: RuntimeDiagnostics,
): GoalIssueFinishedSaveOutcome {
  if (!goalIssueProgressExists(connection, record.parentWorkflowId, record.issueKey)) {
    val recovered = recoverGoalIssueProgress(connection, record)
    if (!recovered.persisted) {
      diagnostics.warning(
        "skillbill sqlite: suppressed goal_issue_finished for ${record.parentWorkflowId}/${record.issueKey}; " +
          "reason=${recovered.suppressionReason}",
        null,
      )
      return recovered
    }
  }
  connection.prepareStatement(
    """
    UPDATE goal_issue_progress SET
      status = ?, subtasks_complete = ?, subtasks_blocked = ?, subtasks_skipped = ?,
      finished_at = ?, mode = ?,
      state_entered_at = CASE
        WHEN COALESCE(status, '') != ? THEN ${nextGoalStateEnteredAtSql("?")}
        ELSE state_entered_at
      END,
      state_entered_at_estimated = CASE
        WHEN COALESCE(status, '') != ? THEN 0
        ELSE state_entered_at_estimated
      END
    WHERE parent_workflow_id = ? AND issue_key = ? AND finished_event_emitted_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.status,
      record.subtasksComplete,
      record.subtasksBlocked,
      record.subtasksSkipped,
      record.finishedAt,
      record.mode,
      record.status,
      record.finishedAt,
      record.finishedAt,
      record.finishedAt,
      record.status,
      record.parentWorkflowId,
      record.issueKey,
    )
    val updated = statement.executeUpdate() > 0
    return GoalIssueFinishedSaveOutcome(
      persisted = updated,
      suppressionReason = if (updated) null else "issue terminal event was already emitted",
    )
  }
}

internal fun nextGoalStateEnteredAtSql(candidateSql: String): String =
  """
  CASE
    WHEN julianday(NULLIF(goal_issue_progress.state_entered_at, '')) IS NULL THEN
      COALESCE(NULLIF($candidateSql, ''), $SQLITE_TIMESTAMP_NOW)
    WHEN julianday(NULLIF($candidateSql, '')) > julianday(goal_issue_progress.state_entered_at) THEN $candidateSql
    WHEN julianday($SQLITE_TIMESTAMP_NOW) > julianday(goal_issue_progress.state_entered_at) THEN $SQLITE_TIMESTAMP_NOW
    ELSE strftime('%Y-%m-%dT%H:%M:%fZ', julianday(goal_issue_progress.state_entered_at) + 0.001 / 86400.0)
  END
  """.trimIndent()

internal fun saveGoalSubtaskFinished(
  connection: Connection,
  record: GoalSubtaskFinishedRecord,
): Boolean {
  val inserted =
    connection.prepareStatement(
      """
      INSERT INTO goal_subtask_events (
        issue_key, workflow_id, subtask_id, subtask_name, status,
        started_at, finished_at, duration_ms, attempt_count, blocked_reason,
        finalizing_agent_id, participating_agent_ids
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (issue_key, subtask_id, workflow_id) DO NOTHING
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        record.issueKey,
        record.workflowId,
        record.subtaskId,
        record.subtaskName,
        record.status,
        record.startedAt,
        record.finishedAt,
        record.durationMs,
        record.attemptCount,
        record.blockedReason,
        record.finalizingAgentId,
        listJson(record.participatingAgentIds),
      )
      statement.executeUpdate() > 0
    }
  connection.prepareStatement(
    """
    UPDATE goal_subtask_events
    SET
      boundary_history_value = COALESCE(
        (SELECT fis.boundary_history_value
         FROM feature_task_workflows ftw
         JOIN feature_implement_sessions fis ON fis.session_id = ftw.session_id
         WHERE ftw.workflow_id = ?),
        'none'
      ),
      boundary_history_written = COALESCE(
        (SELECT fis.boundary_history_written
         FROM feature_task_workflows ftw
         JOIN feature_implement_sessions fis ON fis.session_id = ftw.session_id
         WHERE ftw.workflow_id = ?),
        0
      )
    WHERE issue_key = ? AND subtask_id = ? AND workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.workflowId,
      record.workflowId,
      record.issueKey,
      record.subtaskId,
      record.workflowId,
    )
    statement.executeUpdate()
  }
  return inserted
}

private fun goalRunSessionExists(
  connection: Connection,
  workflowId: String,
): Boolean =
  connection.prepareStatement("SELECT 1 FROM goal_run_sessions WHERE workflow_id = ?").use { statement ->
    statement.bindAll(workflowId)
    statement.executeQuery().use { it.next() }
  }
