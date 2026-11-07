package skillbill.infrastructure.sqlite.idestatus

import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.agentrun.activity.model.AgentRunActivity
import skillbill.ports.agentrun.activity.model.AgentRunActivityKind
import skillbill.ports.agentrun.activity.model.AgentRunActivityState
import skillbill.ports.idestatus.model.StandalonePlanActivity
import java.sql.Connection
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun Connection.writePlanActivity(
  executionId: String,
  activity: List<StandalonePlanActivity>,
) {
  if (activity.isEmpty()) return
  prepareStatement(
    """
    INSERT INTO standalone_plan_activity(execution_id, event_id, phase_id, started_at, updated_at, kind, event_text,
      agent_id, file_path, event_state, duration_ms, exit_code, subtask_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(execution_id, event_id) DO UPDATE SET
      updated_at=excluded.updated_at, event_text=excluded.event_text, event_state=excluded.event_state,
      duration_ms=excluded.duration_ms, exit_code=excluded.exit_code
    """.trimIndent(),
  ).use { statement ->
    activity.forEach { record ->
      val event = record.event
      statement.bindAll(
        executionId, event.id, record.phaseId, record.at.toString(), record.updatedAt.toString(),
        event.kind.wireValue, event.text, event.agentId, event.path, event.state.wireValue,
        event.durationMs, event.exitCode, event.subtaskId,
      )
      statement.addBatch()
    }
    statement.executeBatch()
  }
  activity.map { it.phaseId }.distinct().forEach { phase ->
    prepareStatement(
      "DELETE FROM standalone_plan_activity WHERE execution_id=? AND phase_id=? " +
        "AND kind!='stream' AND event_id NOT IN " +
        "(SELECT event_id FROM standalone_plan_activity WHERE execution_id=? AND phase_id=? " +
        "ORDER BY started_at DESC, event_id DESC LIMIT 128)",
    ).use { statement ->
      statement.bindAll(executionId, phase, executionId, phase)
      statement.executeUpdate()
    }
  }
}

internal fun Connection.readPlanActivity(executionId: String): List<StandalonePlanActivity> =
  prepareStatement(
    "SELECT * FROM standalone_plan_activity WHERE execution_id=? " + "ORDER BY started_at, event_id",
  ).use {
      statement ->
    statement.bindAll(executionId)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          val kind =
            AgentRunActivityKind.entries.firstOrNull { it.wireValue == rows.getString("kind") }
              ?: throw invalidIdeStatusSchemaError(executionId, "kind", "Unknown plan activity kind")
          val state =
            AgentRunActivityState.entries.firstOrNull { it.wireValue == rows.getString("event_state") }
              ?: throw invalidIdeStatusSchemaError(executionId, "event_state", "Unknown plan activity state")
          val started = activityInstant(rows.getString("started_at"), executionId)
          val updated = activityInstant(rows.getString("updated_at"), executionId)
          val duration = rows.getLong("duration_ms").takeUnless { rows.wasNull() }
          val exit = rows.getInt("exit_code").takeUnless { rows.wasNull() }
          val unit = rows.getInt("subtask_id").takeUnless { rows.wasNull() }
          add(
            StandalonePlanActivity(
              rows.getString("phase_id"),
              started,
              updated,
              AgentRunActivity(
                rows.getString("event_id"), kind, rows.getString("event_text"), rows.getString("agent_id"),
                rows.getString("file_path"), state, duration, exit, unit,
              ),
            ),
          )
        }
      }
    }
  }

private fun activityInstant(
  value: String,
  executionId: String,
): Instant =
  try {
    Instant.parse(value)
  } catch (error: DateTimeParseException) {
    throw invalidIdeStatusSchemaError(executionId, "activity_timestamp", error.message.orEmpty())
  }
