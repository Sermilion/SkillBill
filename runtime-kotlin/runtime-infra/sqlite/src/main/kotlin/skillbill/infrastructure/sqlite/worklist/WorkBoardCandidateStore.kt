package skillbill.infrastructure.sqlite.worklist

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkBoardCandidateRow
import java.sql.Connection

private const val MAX_CANDIDATE_BATCH_SIZE = 400

internal fun readWorkBoardCandidates(
  connection: Connection,
  query: WorkBoardCandidateQuery,
): List<WorkBoardCandidateRow> {
  require(query.limit in 1..MAX_CANDIDATE_BATCH_SIZE)
  return connection.prepareStatement(
    """
    WITH bounds(from_value, through_value) AS (VALUES (?, ?)), candidates(id, started_at, terminal, finished_at) AS (
      SELECT g.parent_workflow_id, first_started_at, status IN ('completed', 'failed', 'abandoned'),
        COALESCE(json_extract(c.control_state_json, '$.goal_completed_at'), state_entered_at)
      FROM goal_issue_progress g
      LEFT JOIN goal_runner_controls c ON c.parent_workflow_id = g.parent_workflow_id
      UNION ALL
      SELECT workflow_id, started_at, workflow_status IN ('completed', 'failed', 'abandoned'), finished_at
      FROM feature_task_workflows w
      WHERE NOT EXISTS (SELECT 1 FROM goal_issue_progress g WHERE g.parent_workflow_id = w.workflow_id)
        AND NOT EXISTS (SELECT 1 FROM feature_task_execution_identities i
          WHERE i.workflow_id = w.workflow_id AND i.route_scope = 'goal_child')
        AND NOT EXISTS (SELECT 1 FROM standalone_phase_status s WHERE s.workflow_id = w.workflow_id)
      UNION ALL
      SELECT workflow_id, started_at, workflow_status IN ('completed', 'failed', 'abandoned'), finished_at
      FROM feature_verify_workflows
      UNION ALL
      SELECT invocation_id, started_at, lifecycle_state IN ('terminal', 'completed', 'success', 'failed', 'canceled'), finished_at
      FROM standalone_phase_status s
      WHERE NOT EXISTS (
        SELECT 1 FROM standalone_phase_status newer
        WHERE newer.repository_identity = s.repository_identity AND newer.phase_id = s.phase_id
          AND COALESCE(newer.monitor_pr_key, upper(newer.issue_key), newer.workflow_id, newer.branch_correlation) =
            COALESCE(s.monitor_pr_key, upper(s.issue_key), s.workflow_id, s.branch_correlation)
          AND (length(newer.run_sequence) > length(s.run_sequence)
            OR (length(newer.run_sequence) = length(s.run_sequence) AND newer.run_sequence > s.run_sequence)
            OR (newer.run_sequence = s.run_sequence AND newer.invocation_id > s.invocation_id))
      )
    ), eligible AS (
      SELECT id, CASE WHEN terminal THEN '0' ELSE '1' END || char(10) ||
        ${startedAtKeySql("started_at")} || char(10) || id AS paging_key
      FROM candidates
      WHERE julianday(started_at) <= julianday((SELECT through_value FROM bounds))
        AND (NOT terminal OR finished_at IS NULL OR
          julianday(finished_at) BETWEEN julianday((SELECT from_value FROM bounds))
            AND julianday((SELECT through_value FROM bounds)))
    )
    SELECT id, paging_key FROM eligible WHERE (? IS NULL OR paging_key < ?)
    ORDER BY paging_key DESC LIMIT ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      query.finishedFrom.toString(),
      query.observedAt.toString(),
      query.afterKey,
      query.afterKey,
      query.limit,
    )
    statement.executeQuery().use { rows ->
      buildList { while (rows.next()) add(WorkBoardCandidateRow(rows.getString("id"), rows.getString("paging_key"))) }
    }
  }
}
