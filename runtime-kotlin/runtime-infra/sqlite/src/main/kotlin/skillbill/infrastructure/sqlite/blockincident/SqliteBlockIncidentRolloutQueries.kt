package skillbill.infrastructure.sqlite.blockincident

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentSpoolKeys
import skillbill.ports.blockincident.model.BlockedWorkflowWithoutIncident
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import java.sql.Connection
import java.time.Instant

internal class SqliteBlockIncidentRolloutQueries(
  private val connection: Connection,
) {
  fun activatedAt(): Instant? =
    connection.prepareStatement(
      "SELECT activated_at FROM block_incident_rollout WHERE id = 1",
    ).use { statement ->
      statement.executeQuery().use { rows -> if (rows.next()) Instant.parse(rows.getString(1)) else null }
    }

  fun blockedWithoutIncident(
    since: Instant,
    limit: Int,
  ): List<BlockedWorkflowWithoutIncident> {
    require(limit in 1..MAX_BLOCKED_WITHOUT_INCIDENT)
    val sources =
      listOf(
        WorkflowFamily.TASK_RUNTIME to "feature_task_workflows",
        WorkflowFamily.VERIFY to "feature_verify_workflows",
      )
    val sql =
      sources.joinToString("\nUNION ALL\n") { (family, table) -> orphanSelect(family, table) } +
        "\nORDER BY updated_day, workflow_id LIMIT ?"
    return connection.prepareStatement(sql).use { statement ->
      var index = 0
      repeat(sources.size) {
        statement.setString(++index, since.toString())
        statement.setString(++index, since.toString())
        statement.setString(++index, IDENTITY_STEP_PATH)
        statement.setString(++index, IDENTITY_ALIAS_KIND_PATH)
        statement.setString(++index, IDENTITY_ALIAS_VALUE_PATH)
        statement.setLong(++index, OBSERVATION_SKEW_SECONDS)
      }
      statement.setInt(++index, limit)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(
              BlockedWorkflowWithoutIncident(
                workflowId = rows.getString("workflow_id"),
                family = WorkflowFamily.valueOf(rows.getString("family")),
                blockEnteredAt = Instant.parse(rows.getString("block_entered_at")),
                updatedAt = Instant.parse(rows.getString("updated_iso")),
              ),
            )
          }
        }
      }
    }
  }

  private fun orphanSelect(
    family: WorkflowFamily,
    table: String,
  ): String =
    """
    SELECT t.workflow_id AS workflow_id, '${family.name}' AS family,
           strftime('%Y-%m-%dT%H:%M:%fZ', t.state_entered_at) AS block_entered_at,
           strftime('%Y-%m-%dT%H:%M:%fZ', t.updated_at) AS updated_iso,
           julianday(t.updated_at) AS updated_day
      FROM $table t
     WHERE t.workflow_status = 'blocked'
       AND julianday(t.updated_at) >= julianday(?)
       AND t.state_entered_at_estimated = 0
       AND julianday(t.state_entered_at) >= julianday(?)
       AND NOT CASE
         WHEN COALESCE(
           CASE WHEN json_valid(t.artifacts_json) THEN json_extract(t.artifacts_json, ?) END = t.current_step_id,
           0
         )
         THEN EXISTS (
           SELECT 1 FROM block_incident_aliases a
            WHERE a.alias_kind = json_extract(t.artifacts_json, ?) AND a.alias_value = json_extract(t.artifacts_json, ?)
         )
         ELSE EXISTS (
           SELECT 1 FROM block_incidents i
            WHERE i.workflow_id = t.workflow_id
              AND julianday(i.observed_at) >= julianday(t.state_entered_at) - ? / 86400.0
         )
       END
    """.trimIndent()

  private companion object {
    const val OBSERVATION_SKEW_SECONDS = 300L
    const val MAX_BLOCKED_WITHOUT_INCIDENT = 16
    val IDENTITY_PATH = "$.\"${DurableWorkflowArtifactFamily.GOAL_BLOCK_IDENTITY.label()}\""
    val IDENTITY_STEP_PATH = "$IDENTITY_PATH.\"${SharedPayloadKeys.STEP_ID}\""
    val IDENTITY_ALIAS_KIND_PATH = "$IDENTITY_PATH.\"${BlockIncidentSpoolKeys.ALIAS_KIND}\""
    val IDENTITY_ALIAS_VALUE_PATH = "$IDENTITY_PATH.\"${BlockIncidentSpoolKeys.ALIAS_VALUE}\""
  }
}
