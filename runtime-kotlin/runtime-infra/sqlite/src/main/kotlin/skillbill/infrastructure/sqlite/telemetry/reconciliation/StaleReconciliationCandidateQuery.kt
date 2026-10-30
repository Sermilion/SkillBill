package skillbill.infrastructure.sqlite.telemetry.reconciliation

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import java.sql.Connection
import java.sql.ResultSet
import java.time.temporal.ChronoUnit

private val staleCandidateSelectionSql =
  """
  WITH candidates(family, primary_identity, secondary_identity, stale_at) AS (
    SELECT 'feature_task_runtime', session_id, NULL, started_at
    FROM feature_task_runtime_sessions
    WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL
      AND datetime(started_at) <= datetime(?)
      AND NOT EXISTS (
        SELECT 1 FROM feature_task_workflows workflow
        WHERE workflow.session_id = feature_task_runtime_sessions.session_id
          AND workflow.workflow_status NOT IN ('completed', 'failed', 'abandoned')
          AND datetime(workflow.updated_at) > datetime(?)
      )
    UNION ALL
    SELECT 'feature_verify', session_id, NULL, started_at
    FROM feature_verify_sessions
    WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL
      AND datetime(started_at) <= datetime(?)
      AND NOT EXISTS (
        SELECT 1 FROM feature_verify_workflows workflow
        WHERE workflow.session_id = feature_verify_sessions.session_id
          AND workflow.workflow_status NOT IN ('completed', 'failed', 'abandoned')
          AND datetime(workflow.updated_at) > datetime(?)
      )
    UNION ALL
    SELECT 'quality_check', session_id, NULL, started_at
    FROM quality_check_sessions
    WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL
      AND datetime(started_at) <= datetime(?)
    UNION ALL
    SELECT 'goal_issue', parent_workflow_id, issue_key, last_blocked_at
    FROM goal_issue_progress
    WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL
      AND last_blocked_at IS NOT NULL
      AND latest_segment_workflow_id IS NOT NULL
      AND latest_segment_workflow_id != ''
      AND latest_segment_workflow_id = last_blocked_segment_workflow_id
      AND datetime(last_blocked_at) <= datetime(?)
  )
  SELECT family, primary_identity, secondary_identity
  FROM candidates
  ORDER BY datetime(stale_at), family, primary_identity, COALESCE(secondary_identity, '')
  LIMIT ?
  """.trimIndent()

internal fun reconciliationCandidates(
  connection: Connection,
  request: TelemetryReconciliationRequest,
): List<ReconciliationCandidate> {
  val sessionCutoff = request.now.minus(request.sessionThresholdSeconds, ChronoUnit.SECONDS).toString()
  val goalCutoff = request.now.minus(request.goalIssueAbandonmentDays, ChronoUnit.DAYS).toString()
  return connection.prepareStatement(staleCandidateSelectionSql).use { statement ->
    statement.bindAll(
      sessionCutoff,
      sessionCutoff,
      sessionCutoff,
      sessionCutoff,
      sessionCutoff,
      goalCutoff,
      request.maximumBatchSize,
    )
    statement.executeQuery().use { mapCandidates(it) }
  }
}

private fun mapCandidates(resultSet: ResultSet): List<ReconciliationCandidate> =
  buildList {
    while (resultSet.next()) {
      add(
        ReconciliationCandidate(
          family = resultSet.getString("family"),
          primaryIdentity = resultSet.getString("primary_identity"),
          secondaryIdentity = resultSet.getString("secondary_identity"),
        ),
      )
    }
  }
