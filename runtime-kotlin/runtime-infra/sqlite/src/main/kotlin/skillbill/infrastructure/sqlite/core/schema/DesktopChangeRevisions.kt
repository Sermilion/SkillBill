package skillbill.infrastructure.sqlite.core.schema

import skillbill.contracts.persistence.DesktopReadSchema
import java.sql.Connection
import java.sql.Statement

internal fun installDesktopReadCompatibility(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
  }
  connection.prepareStatement("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)")
    .use { statement ->
      statement.setString(1, DesktopReadSchema.ROOM_IDENTITY_HASH)
      statement.executeUpdate()
    }
  installDesktopChangeRevisions(connection)
  installDesktopReadIndexes(connection)
}

private fun installDesktopChangeRevisions(connection: Connection) {
  val topics =
    mapOf(
      "goal_issue_progress" to listOf(DesktopReadSchema.GOALS_TOPIC),
      "goal_runner_controls" to listOf(DesktopReadSchema.GOALS_TOPIC),
      "goal_planning_preparations" to listOf(DesktopReadSchema.GOALS_TOPIC),
      "goal_shared_preplans" to listOf(DesktopReadSchema.GOALS_TOPIC),
      "goal_subtask_plans" to listOf(DesktopReadSchema.GOALS_TOPIC),
      "goal_subtask_events" to listOf(DesktopReadSchema.GOALS_TOPIC),
      "feature_task_phase_settlements" to DesktopReadSchema.TOPICS,
      "feature_task_workflows" to DesktopReadSchema.TOPICS,
      "feature_task_execution_identities" to listOf(DesktopReadSchema.PHASES_TOPIC),
      "feature_task_runtime_worker_leases" to DesktopReadSchema.TOPICS,
      "feature_verify_workflows" to listOf(DesktopReadSchema.PHASES_TOPIC),
      "standalone_phase_status" to listOf(DesktopReadSchema.PHASES_TOPIC),
      "ide_status_workflow_execution" to DesktopReadSchema.TOPICS,
      "agent_activity_stamps" to DesktopReadSchema.TOPICS,
      "worktree_edit_journal" to DesktopReadSchema.TOPICS,
    )
  connection.createStatement().use { statement ->
    statement.execute(
      "CREATE TABLE ${DesktopReadSchema.REVISIONS_TABLE} " +
        "(topic TEXT PRIMARY KEY, revision INTEGER NOT NULL)",
    )
    DesktopReadSchema.TOPICS.forEach { topic ->
      statement.execute("INSERT INTO ${DesktopReadSchema.REVISIONS_TABLE} VALUES ('$topic', 0)")
    }
    topics.forEach { (table, affected) -> createRevisionTriggers(statement, table, affected) }
  }
}

internal fun separateAgentActivityRevisions(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      "INSERT INTO ${DesktopReadSchema.REVISIONS_TABLE} VALUES ('${DesktopReadSchema.ACTIVITY_TOPIC}', 0)",
    )
    REVISION_TRIGGER_OPERATIONS.forEach { operation ->
      statement.execute("DROP TRIGGER desktop_change_agent_activity_stamps_${operation.lowercase()}")
    }
    createRevisionTriggers(statement, "agent_activity_stamps", listOf(DesktopReadSchema.ACTIVITY_TOPIC))
  }
}

private fun createRevisionTriggers(
  statement: Statement,
  table: String,
  affected: List<String>,
) {
  REVISION_TRIGGER_OPERATIONS.forEach { operation ->
    val updates =
      affected.joinToString(" ") { topic ->
        "UPDATE ${DesktopReadSchema.REVISIONS_TABLE} SET revision = revision + 1 WHERE topic = '$topic';"
      }
    statement.execute(
      "CREATE TRIGGER desktop_change_${table}_${operation.lowercase()} " +
        "AFTER $operation ON $table BEGIN $updates END",
    )
  }
}

internal fun addBlockIncidentRevisionTriggers(connection: Connection) {
  connection.createStatement().use { statement ->
    createRevisionTriggers(statement, "block_incidents", DesktopReadSchema.TOPICS)
  }
}

internal fun addBlockIncidentDiagnosisRevisionTriggers(connection: Connection) {
  connection.createStatement().use { statement ->
    REVISION_TRIGGER_OPERATIONS.forEach { operation ->
      statement.execute("DROP TRIGGER IF EXISTS desktop_change_block_incident_diagnoses_${operation.lowercase()}")
    }
    createRevisionTriggers(statement, "block_incident_diagnoses", DesktopReadSchema.TOPICS)
  }
}

internal fun addBlockIncidentRecoveryRevisionTriggers(connection: Connection) {
  connection.createStatement().use { statement ->
    createRevisionTriggers(statement, "block_incident_recovery_attempts", DesktopReadSchema.TOPICS)
  }
}

internal fun addProductReportRevisionTriggers(connection: Connection) {
  connection.createStatement().use { statement ->
    createRevisionTriggers(statement, "block_incident_reports", DesktopReadSchema.TOPICS)
    createRevisionTriggers(statement, "block_incident_report_links", DesktopReadSchema.TOPICS)
  }
}

private val REVISION_TRIGGER_OPERATIONS = listOf("INSERT", "UPDATE", "DELETE")

private fun installDesktopReadIndexes(connection: Connection) {
  val columns =
    mapOf(
      "goal_issue_progress" to "status, finished_at, julianday(first_started_at), parent_workflow_id",
      "feature_task_workflows" to "workflow_status, finished_at, julianday(started_at), workflow_id",
      "feature_verify_workflows" to "workflow_status, finished_at, julianday(started_at), workflow_id",
      "standalone_phase_status" to "finished_at, lifecycle_state, julianday(started_at), invocation_id",
    )
  connection.createStatement().use { statement ->
    columns.forEach { (table, fields) ->
      statement.execute("CREATE INDEX desktop_live_$table ON $table ($fields)")
    }
    statement.execute(
      "CREATE INDEX desktop_phase_latest ON standalone_phase_status " +
        "(repository_identity, phase_id, COALESCE(upper(issue_key), workflow_id, invocation_id), " +
        "length(run_sequence), run_sequence)",
    )
    statement.execute(
      "CREATE INDEX desktop_workflow_latest ON ide_status_workflow_execution " +
        "(workflow_id, length(run_sequence), run_sequence)",
    )
  }
}
