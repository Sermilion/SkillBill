package skillbill.infrastructure.sqlite.telemetry.goal

import skillbill.contracts.telemetry.GoalRunTerminalReason
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.telemetry.lifecycle.listJson
import skillbill.infrastructure.sqlite.telemetry.lifecycle.toSqlInt
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.lifecycle.GoalStartedSupersession
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.workflow.model.WorkflowStatus
import java.sql.Connection
import java.time.Duration
import java.time.Instant

private const val SQLITE_TIMESTAMP_NOW = "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"

internal enum class GoalStartedSaveOutcome { INSERTED, DUPLICATE }

internal enum class GoalFinishedSaveOutcome { FIRST_TERMINAL, DUPLICATE }

internal data class GoalIssueFinishedSaveOutcome(val persisted: Boolean, val suppressionReason: String? = null)

internal data class GoalSupersessionResult(
  val invocationWorkflowIds: List<String>,
  val abandonedIssues: List<GoalIssueFinishedRecord>,
)

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

internal fun supersedeEligibleSegments(
  connection: Connection,
  record: GoalStartedRecord,
  candidates: List<GoalStartedSupersession>,
): GoalSupersessionResult {
  val startedAt = Instant.parse(record.startedAt)
  val transitioned = mutableListOf<GoalStartedSupersession>()
  candidates.groupBy { it.candidate.workflowId }.values.forEach { decisions ->
    val owners = decisions.flatMap {
      listOfNotNull(it.candidate.parentWorkflowId, it.candidate.latestSegmentParentWorkflowId)
    }.distinct()
    if (decisions.any { !it.eligible } || owners.size > 1) return@forEach
    val candidate = decisions.first()
    val previousStart = Instant.parse(candidate.candidate.startedAt)
    if (!previousStart.isBefore(startedAt)) return@forEach
    val elapsed = Duration.between(previousStart, startedAt).toMillis().coerceAtLeast(0)
    val changed =
      connection.prepareStatement(
        """
        UPDATE goal_run_sessions SET
          status = '${WorkflowStatus.ABANDONED.wireValue}', finished_at = ?, finished_duration_ms = ?,
          subtasks_complete = ?, subtasks_blocked = ?, subtasks_skipped = ?,
          stop_reason = ?
        WHERE workflow_id = ? AND status IS NULL AND finished_at IS NULL
          AND UPPER(TRIM(issue_key)) = UPPER(TRIM(?))
          AND started_at = ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          record.startedAt,
          elapsed,
          candidate.complete.coerceAtLeast(0),
          candidate.blocked.coerceAtLeast(0),
          candidate.skipped.coerceAtLeast(0),
          GoalRunTerminalReason.SUPERSEDED_BY_NEW_RUN.wireValue,
          candidate.candidate.workflowId,
          record.issueKey,
          candidate.candidate.startedAt,
        )
        statement.executeUpdate() == 1
      }
    if (changed) transitioned += candidate
  }
  val abandonedIssues = transitioned.mapNotNull { segment ->
    val parent = segment.candidate.parentWorkflowId ?: segment.candidate.latestSegmentParentWorkflowId
      ?: return@mapNotNull null
    if (parent == record.parentWorkflowId || !segment.confirmedNonLiveOwner) return@mapNotNull null
    val aggregates = latestIssueSegments(connection, parent, record.issueKey)
    val aggregate = aggregates.singleOrNull { it.latestWorkflowId == segment.candidate.workflowId }
      ?: return@mapNotNull null
    if (hasOpenIssueSegment(connection, parent, aggregate.issueKey)) return@mapNotNull null
    GoalIssueFinishedRecord(
      issueKey = aggregate.issueKey,
      parentWorkflowId = parent,
      status = WorkflowStatus.ABANDONED.wireValue,
      subtasksComplete = segment.complete.coerceAtLeast(0),
      subtasksBlocked = segment.blocked.coerceAtLeast(0),
      subtasksSkipped = segment.skipped.coerceAtLeast(0),
      finishedAt = record.startedAt,
      mode = segment.candidate.mode,
    )
  }.distinctBy { it.parentWorkflowId to it.issueKey }
  return GoalSupersessionResult(transitioned.map { it.candidate.workflowId }, abandonedIssues)
}

private data class LatestIssueSegment(val issueKey: String, val latestWorkflowId: String)

private fun latestIssueSegments(connection: Connection, parent: String, issue: String): List<LatestIssueSegment> =
  connection.prepareStatement(
    """
    SELECT issue_key, latest_segment_workflow_id FROM goal_issue_progress
    WHERE parent_workflow_id = ? AND UPPER(TRIM(issue_key)) = UPPER(TRIM(?))
      AND status IN ('running', 'blocked') AND finished_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(parent, issue)
    statement.executeQuery().use { rows -> buildList {
      while (rows.next()) add(LatestIssueSegment(rows.getString("issue_key"), rows.getString("latest_segment_workflow_id")))
    } }
  }

private fun hasOpenIssueSegment(connection: Connection, parent: String, issue: String): Boolean =
  connection.prepareStatement(
    """
    SELECT 1 FROM goal_run_sessions
    WHERE UPPER(TRIM(issue_key)) = UPPER(TRIM(?))
      AND (status IS NULL OR finished_at IS NULL)
      AND (
        parent_workflow_id = ? OR EXISTS (
          SELECT 1 FROM goal_issue_progress progress
          WHERE progress.latest_segment_workflow_id = goal_run_sessions.workflow_id
            AND progress.parent_workflow_id = ?
        )
      )
    LIMIT 1
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(issue, parent, parent)
    statement.executeQuery().use { rows -> rows.next() }
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
      no_change_reason = ?,
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
      record.noChangeReason,
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
      no_change_reason, parent_workflow_id
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
      record.noChangeReason,
      record.parentWorkflowId,
    )
    statement.executeUpdate()
  }
}

internal fun recordGoalIssueSegmentStarted(
  connection: Connection,
  segment: GoalIssueSegmentStart,
): Boolean {
  val persistedIssueKey = resolveOpenGoalIssueKey(connection, segment.parentWorkflowId, segment.issueKey) ?: return false
  val identity = segment.copy(issueKey = persistedIssueKey)
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
      AND COALESCE(goal_issue_progress.status, '') NOT IN ('completed', 'completed_no_change', 'failed', 'abandoned')
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      identity.parentWorkflowId,
      identity.issueKey,
      identity.resumed.toSqlInt(),
      identity.startedAt,
      identity.startedAt,
      identity.workflowId,
      identity.mode,
      identity.startedAt,
    )
    statement.executeUpdate()
  }
  return true
}

internal fun resolveOpenGoalIssueKey(connection: Connection, parent: String, requestedIssue: String): String? {
  val identities = connection.prepareStatement(
    """
    SELECT issue_key FROM goal_issue_progress
    WHERE parent_workflow_id = ? AND UPPER(TRIM(issue_key)) = UPPER(TRIM(?))
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(parent, requestedIssue)
    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
  }
  return when (identities.size) {
    0 -> requestedIssue
    1 -> identities.single()
    else -> null
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
  val persistedIssueKey = connection.prepareStatement(
    "SELECT issue_key FROM goal_issue_progress WHERE parent_workflow_id = ? AND latest_segment_workflow_id = ?",
  ).use { statement ->
    statement.bindAll(parentWorkflowId, workflowId)
    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else issueKey }
  }
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
      AND latest_segment_workflow_id = ?
      AND finished_at IS NULL
      AND COALESCE(status, '') NOT IN ('completed', 'completed_no_change', 'failed', 'abandoned')
    """.trimIndent(),
  ).use { statement ->
    val tail = listOf(status, status, status, parentWorkflowId, persistedIssueKey, workflowId)
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
      finished_at = ?, mode = ?, no_change_reason = ?,
      state_entered_at = CASE
        WHEN COALESCE(status, '') != ? THEN ${nextGoalStateEnteredAtSql("?")}
        ELSE state_entered_at
      END,
      state_entered_at_estimated = CASE
        WHEN COALESCE(status, '') != ? THEN 0
        ELSE state_entered_at_estimated
      END
    WHERE parent_workflow_id = ?
      AND issue_key = ?
      AND finished_at IS NULL
      AND COALESCE(status, '') NOT IN ('completed', 'completed_no_change', 'failed', 'abandoned')
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.status,
      record.subtasksComplete,
      record.subtasksBlocked,
      record.subtasksSkipped,
      record.finishedAt,
      record.mode,
      record.noChangeReason,
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
      suppressionReason = if (updated) null else "issue finish already recorded",
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
