package skillbill.infrastructure.sqlite

import skillbill.ports.blockincident.BlockedWorkflowWithoutIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import java.sql.Connection
import java.time.Instant

internal class SqliteBlockIncidentRolloutQueries(
  private val connection: Connection,
  private val loadIncident: (String) -> BlockIncident?,
) {
  fun activatedAt(): Instant? = connection.prepareStatement(
    "SELECT activated_at FROM block_incident_rollout WHERE id = 1",
  ).use { statement ->
    statement.executeQuery().use { rows -> if (rows.next()) Instant.parse(rows.getString(1)) else null }
  }

  fun latestForWorkflow(workflowId: String): BlockIncident? =
    connection.prepareStatement(
      "SELECT occurrence_token FROM block_incidents WHERE workflow_id = ? ORDER BY observed_at DESC LIMIT 1",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) null else loadIncident(rows.getString(1))
      }
    }

  fun blockedWithoutIncident(since: Instant, limit: Int): List<BlockedWorkflowWithoutIncident> {
    require(limit in 1..16)
    val candidates = connection.prepareStatement(
      """
      SELECT workflow_id, current_step_id, artifacts_json, updated_at, 'phase' AS kind
        FROM feature_task_workflows WHERE workflow_status = 'blocked' AND updated_at >= ?
      UNION ALL
      SELECT workflow_id, current_step_id, artifacts_json, updated_at, 'goal' AS kind
        FROM feature_verify_workflows WHERE workflow_status = 'blocked' AND updated_at >= ?
      ORDER BY updated_at LIMIT ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, since.toString())
      statement.setString(2, since.toString())
      statement.setInt(3, limit * 4)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val workflowId = rows.getString("workflow_id")
            val updatedAt = Instant.parse(rows.getString("updated_at"))
            val alreadyObserved = connection.prepareStatement(
              "SELECT 1 FROM block_incidents WHERE workflow_id = ? AND observed_at >= ? LIMIT 1",
            ).use { check ->
              check.setString(1, workflowId)
              check.setString(2, updatedAt.minusSeconds(300).toString())
              check.executeQuery().use { it.next() }
            }
            if (!alreadyObserved) {
              add(
                BlockedWorkflowWithoutIncident(
                  workflowId = workflowId,
                  parentWorkflowId = null,
                  kind = if (rows.getString("kind") == "goal") {
                    BlockedWorkflowWithoutIncident.Kind.GOAL
                  } else {
                    BlockedWorkflowWithoutIncident.Kind.PHASE
                  },
                  blockedReason = blockedReason(rows.getString("artifacts_json")),
                  phaseId = rows.getString("current_step_id"),
                  attempt = null,
                  updatedAt = updatedAt,
                ),
              )
            }
          }
        }
      }
    }
    return candidates.take(limit)
  }

  private fun blockedReason(raw: String): String =
    Regex("\\\"blocked_reason\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(raw)?.groupValues?.get(1)
      ?: "A workflow remains blocked, but its persisted reason could not be recovered."
}
