package skillbill.infrastructure.sqlite.worklist

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.work.model.WorkActivityAttempt
import skillbill.ports.work.model.WorkActivityStop
import java.sql.Connection

internal fun readGoalActivityAttempts(
  connection: Connection,
  parentWorkflowId: String,
  offset: Int,
  limit: Int,
): List<WorkActivityAttempt> {
  require(offset >= 0 && limit in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
  return connection.prepareStatement(ATTEMPTS_SQL).use { statement ->
    statement.bindAll(parentWorkflowId, parentWorkflowId, limit, offset)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          val id = rows.required("workflow_id")
          add(
            WorkActivityAttempt(
              id,
              rows.getInt("subtask_id"),
              rows.getString("subtask_name"),
              rows.getString("workflow_status"),
              rows.getString("event_status"),
              parseInstant(rows.required("started_at"), id, "started_at"),
              rows.getString("finished_at")?.takeIf(String::isNotBlank)?.let { parseInstant(it, id, "finished_at") },
              rows.getString("blocked_reason"),
            ),
          )
        }
      }
    }
  }
}

internal fun readGoalActivityStops(
  connection: Connection,
  parentWorkflowId: String,
  offset: Int,
  limit: Int,
): List<WorkActivityStop> {
  require(offset >= 0 && limit in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
  return connection.prepareStatement(
    """
    SELECT workflow_id, started_at, finished_at, stop_reason, blocked_reason FROM goal_run_sessions
    WHERE parent_workflow_id = ? OR workflow_id = ?
    ORDER BY started_at, workflow_id LIMIT ? OFFSET ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(parentWorkflowId, parentWorkflowId, limit, offset)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          val id = rows.required("workflow_id")
          add(
            WorkActivityStop(
              id,
              parseInstant(rows.required("started_at"), id, "started_at"),
              rows.getString("finished_at")?.let { parseInstant(it, id, "finished_at") },
              rows.getString("stop_reason"),
              rows.getString("blocked_reason"),
            ),
          )
        }
      }
    }
  }
}

private val ATTEMPTS_SQL =
  """
  WITH children AS (
    SELECT workflow_id, goal_subtask_id AS subtask_id FROM feature_task_workflows WHERE parent_workflow_id = ?
    UNION
    SELECT workflow_id, goal_subtask_id AS subtask_id FROM feature_task_runtime_sessions WHERE goal_parent_workflow_id = ?
  )
  SELECT c.workflow_id, c.subtask_id, e.subtask_name, w.workflow_status, e.status AS event_status,
    COALESCE(w.started_at, e.started_at, s.started_at) AS started_at,
    COALESCE(w.finished_at, e.finished_at, s.finished_at) AS finished_at,
    e.blocked_reason
  FROM children c
  LEFT JOIN feature_task_workflows w ON w.workflow_id = c.workflow_id
  LEFT JOIN feature_task_runtime_sessions s ON s.workflow_id = c.workflow_id
  LEFT JOIN goal_subtask_events e ON e.workflow_id = c.workflow_id AND e.subtask_id = c.subtask_id
  WHERE c.subtask_id IS NOT NULL
  ORDER BY started_at, c.subtask_id, c.workflow_id LIMIT ? OFFSET ?
  """.trimIndent()
