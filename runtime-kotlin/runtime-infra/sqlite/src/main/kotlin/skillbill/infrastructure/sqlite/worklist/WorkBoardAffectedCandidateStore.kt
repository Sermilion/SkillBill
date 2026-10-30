package skillbill.infrastructure.sqlite.worklist

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.core.schema.standalonePhaseGroupMatch
import skillbill.ports.work.model.WORK_BOARD_CANDIDATE_BATCH_SIZE
import skillbill.ports.work.model.WorkBoardAffectedCandidates
import java.sql.Connection
import java.time.Instant

internal fun readWorkBoardAffectedCandidates(
  connection: Connection,
  keys: Set<String>,
  finishedFrom: Instant,
  observedAt: Instant,
): WorkBoardAffectedCandidates {
  if (keys.isEmpty()) return WorkBoardAffectedCandidates(emptySet(), emptySet())
  val expanded =
    connection.prepareStatement(
      """
      WITH RECURSIVE changed(id) AS (SELECT value FROM json_each(?)), expanded(id) AS (
        SELECT id FROM changed
        UNION
        SELECT dependency.id FROM expanded current
        JOIN (
          SELECT status.invocation_id AS source_id, status.workflow_id AS id
          FROM standalone_phase_status status WHERE status.workflow_id IS NOT NULL
          UNION ALL
          SELECT execution.invocation_id, execution.workflow_id
          FROM ide_status_workflow_execution execution
          UNION ALL
          SELECT execution.workflow_id, execution.invocation_id
          FROM ide_status_workflow_execution execution
          UNION ALL
          SELECT status.workflow_id, status.invocation_id
          FROM standalone_phase_status status WHERE status.workflow_id IS NOT NULL
          UNION ALL
          SELECT source.invocation_id, peer.invocation_id
          FROM standalone_phase_status source
          JOIN standalone_phase_status peer ON ${standalonePhaseGroupMatch("peer", "source")}
          WHERE source.invocation_id IS NOT NULL
          UNION ALL
          SELECT identity.workflow_id, progress.parent_workflow_id
          FROM feature_task_execution_identities identity
          JOIN goal_issue_progress progress ON upper(trim(progress.issue_key)) = identity.normalized_issue_key
          WHERE identity.route_scope = 'goal_child'
          UNION ALL
          SELECT issue.workflow_id, verify.workflow_id
          FROM (
            SELECT workflow_id, issue_key FROM feature_task_workflows
            UNION ALL SELECT parent_workflow_id, issue_key FROM goal_issue_progress
            UNION ALL SELECT workflow_id, normalized_issue_key FROM feature_task_execution_identities
          ) issue
          JOIN feature_verify_workflows verify ON upper(trim(issue.issue_key)) = upper(trim(verify.issue_key))
        ) dependency ON dependency.source_id = current.id
      ) SELECT id FROM expanded WHERE id IS NOT NULL LIMIT ${WORK_BOARD_CANDIDATE_BATCH_SIZE + 1}
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, JsonCodec.valueToJsonString(keys.sorted()))
      statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
    }
  if (expanded.size > WORK_BOARD_CANDIDATE_BATCH_SIZE) {
    return WorkBoardAffectedCandidates(emptySet(), emptySet(), requiresRebuild = true)
  }
  val candidates = readWorkBoardCandidatesByIds(connection, expanded, finishedFrom, observedAt)
  return WorkBoardAffectedCandidates(candidates, expanded - candidates)
}
