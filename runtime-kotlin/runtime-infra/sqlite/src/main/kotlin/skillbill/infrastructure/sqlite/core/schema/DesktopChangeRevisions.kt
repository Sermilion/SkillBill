package skillbill.infrastructure.sqlite.core.schema

import skillbill.contracts.persistence.DesktopReadSchema
import java.sql.Connection
import java.sql.Statement
import java.util.UUID

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

internal fun addQaOutcomeRevisionTriggers(connection: Connection) {
  connection.createStatement().use { statement ->
    REVISION_TRIGGER_OPERATIONS.forEach { operation ->
      statement.execute("DROP TRIGGER IF EXISTS desktop_change_feature_task_qa_outcomes_${operation.lowercase()}")
    }
    createRevisionTriggers(
      statement,
      "feature_task_qa_outcomes",
      listOf(DesktopReadSchema.GOALS_TOPIC, DesktopReadSchema.PHASES_TOPIC),
    )
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

internal fun separateDesktopChangeSignals(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      "CREATE TABLE IF NOT EXISTS ${DesktopReadSchema.CHANGE_ITEMS_TABLE} " +
        "(topic TEXT NOT NULL, revision INTEGER NOT NULL, work_id TEXT NOT NULL, " +
        "PRIMARY KEY (topic, revision, work_id))",
    )
    statement.execute(
      "CREATE TABLE IF NOT EXISTS ${DesktopReadSchema.GENERATION_TABLE} " +
        "(id INTEGER PRIMARY KEY CHECK (id = 1), generation TEXT NOT NULL)",
    )
    statement.execute(
      "INSERT OR IGNORE INTO ${DesktopReadSchema.REVISIONS_TABLE} VALUES ('${DesktopReadSchema.SPECS_TOPIC}', 0)",
    )
  }
  connection.prepareStatement(
    "INSERT OR IGNORE INTO ${DesktopReadSchema.GENERATION_TABLE} (id, generation) VALUES (1, ?)",
  ).use { statement ->
    statement.setString(1, UUID.randomUUID().toString())
    statement.executeUpdate()
  }
  installDesktopChangeTriggers(connection)
}

internal fun installDesktopChangeTriggers(connection: Connection) {
  val columns = DESKTOP_CHANGE_TABLES.associate { table -> table.name to connection.tableColumns(table.name) }
  connection.createStatement().use { statement ->
    DESKTOP_CHANGE_TABLES.forEach { table ->
      TRIGGER_SUFFIXES.forEach { suffix -> statement.execute("DROP TRIGGER IF EXISTS ${triggerName(table, suffix)}") }
    }
    DESKTOP_CHANGE_TABLES.forEach { table -> createChangeTriggers(statement, table, columns.getValue(table.name)) }
  }
}

private fun createChangeTriggers(
  statement: Statement,
  table: DesktopChangeTable,
  columns: List<String>,
) {
  statement.createChangeTrigger(table, INSERT, table.topics, null)
  if (table.insertOnly) return
  statement.createChangeTrigger(table, DELETE, table.topics, null)
  if (table.name != LEASES_TABLE) {
    statement.createChangeTrigger(table, UPDATE, table.topics, anyDiffers(columns))
    return
  }
  val heartbeatColumns = columns.filter { column -> column in LEASE_HEARTBEAT_COLUMNS }
  statement.createChangeTrigger(table, UPDATE, table.topics, anyDiffers(LEASE_IDENTITY_COLUMNS))
  statement.createChangeTrigger(
    table,
    UPDATE,
    listOf(DesktopReadSchema.ACTIVITY_TOPIC),
    "NOT (${anyDiffers(LEASE_IDENTITY_COLUMNS)}) AND (${anyDiffers(heartbeatColumns)})",
    suffix = ACTIVITY_UPDATE_SUFFIX,
  )
}

private fun Statement.createChangeTrigger(
  table: DesktopChangeTable,
  operation: String,
  topics: List<String>,
  condition: String?,
  suffix: String = operation.lowercase(),
) {
  val row = if (operation == DELETE) "OLD" else "NEW"
  val keys = table.keys(row)
  val body = topics.joinToString(" ") { topic -> changeStatements(topic, keys) }
  val whenClause = condition?.let { " WHEN $it" }.orEmpty()
  execute("CREATE TRIGGER ${triggerName(table, suffix)} AFTER $operation ON ${table.name}$whenClause BEGIN $body END")
}

internal fun changeStatements(
  topic: String,
  keys: String,
): String {
  val revision = "(SELECT revision FROM ${DesktopReadSchema.REVISIONS_TABLE} WHERE topic = '$topic')"
  return "UPDATE ${DesktopReadSchema.REVISIONS_TABLE} SET revision = revision + 1 WHERE topic = '$topic'; " +
    "INSERT OR IGNORE INTO ${DesktopReadSchema.CHANGE_ITEMS_TABLE} (topic, revision, work_id) " +
    "SELECT DISTINCT '$topic', $revision, k FROM ($keys) WHERE k IS NOT NULL; " +
    "DELETE FROM ${DesktopReadSchema.CHANGE_ITEMS_TABLE} WHERE topic = '$topic' " +
    "AND revision <= $revision - ${DesktopReadSchema.CHANGE_ITEM_RETENTION};"
}

private fun anyDiffers(columns: List<String>): String =
  columns.joinToString(" OR ") { column -> "OLD.$column IS NOT NEW.$column" }.ifEmpty { "0" }

private fun Connection.tableColumns(table: String): List<String> =
  prepareStatement("SELECT name FROM pragma_table_info(?) ORDER BY cid").use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) add(rows.getString(1))
      }
    }
  }

private fun triggerName(
  table: DesktopChangeTable,
  suffix: String,
): String = "desktop_change_${table.name}_$suffix"

private class DesktopChangeTable(
  val name: String,
  val topics: List<String>,
  val insertOnly: Boolean = false,
  val keys: (String) -> String,
)

private fun column(name: String): (String) -> String = { row -> "SELECT $row.$name AS k" }

private val invocationKey: (String) -> String = { row -> "SELECT COALESCE($row.invocation_id, $row.workflow_id) AS k" }

private val incidentKey: (String) -> String = { row ->
  "SELECT workflow_id AS k FROM block_incidents WHERE occurrence_token = $row.occurrence_token"
}

private val reportKey: (String) -> String = { row ->
  "SELECT incidents.workflow_id AS k FROM block_incident_report_links AS links " +
    "JOIN block_incidents AS incidents ON incidents.occurrence_token = links.occurrence_token " +
    "WHERE links.report_id = $row.report_id"
}

private const val INSERT = "INSERT"
private const val UPDATE = "UPDATE"
private const val DELETE = "DELETE"
private const val ACTIVITY_UPDATE_SUFFIX = "update_activity"
private const val LEASES_TABLE = "feature_task_runtime_worker_leases"
private const val WORKFLOW_ID = "workflow_id"
private val TRIGGER_SUFFIXES = listOf("insert", "update", "delete", ACTIVITY_UPDATE_SUFFIX)
private val LEASE_IDENTITY_COLUMNS = listOf("lease_state", "owner_token", "pid", "phase_id", "phase_attempt")
private val LEASE_HEARTBEAT_COLUMNS = listOf("heartbeat_at", "expires_at")
private val GOAL_SPEC_TOPICS = listOf(DesktopReadSchema.GOALS_TOPIC, DesktopReadSchema.SPECS_TOPIC)
private val PHASE_TOPICS = listOf(DesktopReadSchema.PHASES_TOPIC)

private val DESKTOP_CHANGE_TABLES =
  listOf(
    DesktopChangeTable("goal_issue_progress", GOAL_SPEC_TOPICS, keys = column("parent_workflow_id")),
    DesktopChangeTable("goal_runner_controls", GOAL_SPEC_TOPICS, keys = column("parent_workflow_id")),
    DesktopChangeTable("goal_planning_preparations", GOAL_SPEC_TOPICS, keys = column("parent_goal_workflow_id")),
    DesktopChangeTable("goal_shared_preplans", GOAL_SPEC_TOPICS, keys = column("parent_goal_workflow_id")),
    DesktopChangeTable("goal_subtask_plans", GOAL_SPEC_TOPICS, keys = column("parent_goal_workflow_id")),
    DesktopChangeTable("goal_subtask_events", GOAL_SPEC_TOPICS, keys = column(WORKFLOW_ID)),
    DesktopChangeTable("feature_task_phase_settlements", DesktopReadSchema.TOPICS, keys = column(WORKFLOW_ID)),
    DesktopChangeTable("feature_task_workflows", DesktopReadSchema.TOPICS, keys = column(WORKFLOW_ID)),
    DesktopChangeTable("feature_task_qa_outcomes", DesktopReadSchema.TOPICS, keys = column(WORKFLOW_ID)),
    DesktopChangeTable("block_incidents", DesktopReadSchema.TOPICS, keys = column(WORKFLOW_ID)),
    DesktopChangeTable("feature_task_execution_identities", PHASE_TOPICS, keys = column(WORKFLOW_ID)),
    DesktopChangeTable("feature_verify_workflows", PHASE_TOPICS, keys = column(WORKFLOW_ID)),
    DesktopChangeTable("standalone_phase_status", PHASE_TOPICS, keys = invocationKey),
    DesktopChangeTable("ide_status_workflow_execution", DesktopReadSchema.TOPICS, keys = invocationKey),
    DesktopChangeTable(
      "agent_activity_stamps",
      listOf(DesktopReadSchema.ACTIVITY_TOPIC),
      keys = column(WORKFLOW_ID),
    ),
    DesktopChangeTable(
      "worktree_edit_journal",
      listOf(DesktopReadSchema.ACTIVITY_TOPIC),
      insertOnly = true,
      keys = column(WORKFLOW_ID),
    ),
    DesktopChangeTable("block_incident_diagnoses", DesktopReadSchema.TOPICS, keys = incidentKey),
    DesktopChangeTable("block_incident_recovery_attempts", DesktopReadSchema.TOPICS, keys = incidentKey),
    DesktopChangeTable("block_incident_report_links", DesktopReadSchema.TOPICS, keys = incidentKey),
    DesktopChangeTable("block_incident_reports", DesktopReadSchema.TOPICS, keys = reportKey),
    DesktopChangeTable(LEASES_TABLE, DesktopReadSchema.TOPICS, keys = column(WORKFLOW_ID)),
  )

internal fun installWorkBoardChangeTriggers(connection: Connection) {
  val tables =
    listOf(
      "goal_issue_progress", "goal_runner_controls", "goal_planning_preparations", "goal_subtask_plans",
      "feature_task_workflows", "feature_task_execution_identities", "feature_verify_workflows",
      "standalone_phase_status", "ide_status_workflow_execution",
    )
  connection.createStatement().use { statement ->
    tables.forEach { table ->
      listOf("insert", "update", "delete").forEach { suffix ->
        statement.execute("DROP TRIGGER IF EXISTS desktop_change_${table}_$suffix")
      }
      installTableTriggers(statement, table)
    }
  }
}

private fun installTableTriggers(
  statement: Statement,
  table: String,
) {
  val topics =
    when (table) {
      "goal_issue_progress", "goal_runner_controls", "goal_planning_preparations", "goal_subtask_plans" ->
        listOf(DesktopReadSchema.GOALS_TOPIC, DesktopReadSchema.SPECS_TOPIC)
      "feature_task_execution_identities", "feature_verify_workflows" -> listOf(DesktopReadSchema.PHASES_TOPIC)
      "standalone_phase_status" -> listOf(DesktopReadSchema.PHASES_TOPIC)
      else -> DesktopReadSchema.TOPICS
    }
  listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
    val suffix = operation.lowercase()
    val rowNames =
      when (operation) {
        "INSERT" -> listOf("NEW")
        "DELETE" -> listOf("OLD")
        else -> listOf("OLD", "NEW")
      }
    val keys = rowNames.map { row -> affectedKeys(table, row) }.joinToString(" UNION ")
    val whenClause = if (operation == "UPDATE") " WHEN ${changedColumns(statement, table)}" else ""
    val body = topics.joinToString(" ") { topic -> changeStatements(topic, keys) }
    statement.execute(
      "CREATE TRIGGER desktop_change_${table}_$suffix AFTER $operation ON $table$whenClause BEGIN $body END",
    )
  }
}

private fun changedColumns(
  statement: Statement,
  table: String,
): String {
  val columns =
    statement.connection.prepareStatement("SELECT name FROM pragma_table_info(?) ORDER BY cid").use { query ->
      query.setString(1, table)
      query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }
  return columns.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }.ifEmpty { "0" }
}

private fun affectedKeys(
  table: String,
  row: String,
): String {
  return (listOf(originalKey(table, row)) + relatedKeys(table, row)).joinToString(" UNION ")
}

private fun originalKey(
  table: String,
  row: String,
): String =
  when (table) {
    "goal_issue_progress" -> "SELECT $row.parent_workflow_id AS k"
    "goal_runner_controls" -> "SELECT $row.parent_workflow_id AS k"
    "goal_planning_preparations", "goal_subtask_plans" -> "SELECT $row.parent_goal_workflow_id AS k"
    "feature_task_workflows", "feature_task_execution_identities", "feature_verify_workflows" ->
      "SELECT $row.workflow_id AS k"
    "standalone_phase_status", "ide_status_workflow_execution" ->
      "SELECT COALESCE($row.invocation_id, $row.workflow_id) AS k UNION SELECT $row.invocation_id AS k " +
        "UNION SELECT $row.workflow_id AS k"
    else -> "SELECT NULL AS k"
  }

private fun relatedKeys(
  table: String,
  row: String,
): List<String> =
  when (table) {
    "goal_issue_progress" ->
      listOf(
        "SELECT parent_workflow_id AS k FROM goal_issue_progress " +
          "WHERE upper(trim(issue_key)) = upper(trim($row.issue_key))",
        "SELECT workflow_id AS k FROM feature_verify_workflows " +
          "WHERE upper(trim(issue_key)) = upper(trim($row.issue_key))",
        standaloneForWorkflow("$row.parent_workflow_id"),
      )
    "goal_runner_controls", "goal_planning_preparations", "goal_subtask_plans" ->
      listOf(
        "SELECT parent_workflow_id AS k FROM goal_issue_progress WHERE parent_workflow_id = " + parentId(table, row),
        "SELECT verify.workflow_id AS k FROM feature_verify_workflows verify JOIN goal_issue_progress goal " +
          "ON upper(trim(goal.issue_key)) = upper(trim(verify.issue_key)) " +
          "WHERE goal.parent_workflow_id = ${parentId(table, row)}",
        "SELECT verify.workflow_id AS k FROM feature_verify_workflows verify JOIN feature_task_workflows goal " +
          "ON upper(trim(goal.issue_key)) = upper(trim(verify.issue_key)) " +
          "WHERE goal.workflow_id = ${parentId(table, row)}",
        standaloneForWorkflow(parentId(table, row)),
      )
    "feature_task_workflows" ->
      listOf(
        parentsForWorkflow("$row.workflow_id"),
        "SELECT workflow_id AS k FROM feature_verify_workflows " +
          "WHERE upper(trim(issue_key)) = upper(trim($row.issue_key))",
        standaloneForWorkflow("$row.workflow_id"),
      )
    "feature_task_execution_identities" ->
      listOf(
        "SELECT progress.parent_workflow_id AS k FROM goal_issue_progress progress " +
          "WHERE upper(trim(progress.issue_key)) = upper(trim($row.normalized_issue_key))",
        "SELECT workflow_id AS k FROM feature_verify_workflows " +
          "WHERE upper(trim(issue_key)) = upper(trim($row.normalized_issue_key))",
        parentsForWorkflow("$row.workflow_id"),
        standaloneForWorkflow("$row.workflow_id"),
      )
    "feature_verify_workflows" ->
      listOf(
        "SELECT workflow_id AS k FROM feature_verify_workflows " +
          "WHERE upper(trim(issue_key)) = upper(trim($row.issue_key))",
      )
    "standalone_phase_status" ->
      listOf(
        "SELECT $row.workflow_id AS k",
        standaloneGroupPeers(row),
        standaloneForWorkflow("$row.workflow_id"),
        parentsForWorkflow("$row.workflow_id"),
        "SELECT workflow_id AS k FROM feature_verify_workflows " +
          "WHERE upper(trim(issue_key)) = upper(trim($row.issue_key))",
      )
    "ide_status_workflow_execution" ->
      listOf(
        "SELECT $row.invocation_id AS k",
        "SELECT $row.workflow_id AS k",
        standaloneForWorkflow("$row.invocation_id"),
        standaloneForWorkflow("$row.workflow_id"),
        parentsForWorkflow("$row.workflow_id"),
        "SELECT workflow_id AS k FROM feature_verify_workflows " +
          "WHERE upper(trim(issue_key)) = upper(trim($row.issue_key))",
        "SELECT workflow_id AS k FROM feature_verify_workflows WHERE upper(trim(issue_key)) = " +
          "(SELECT upper(trim(issue_key)) FROM feature_task_workflows WHERE workflow_id = $row.workflow_id)",
      )
    else -> emptyList()
  }

private fun parentId(
  table: String,
  row: String,
): String = if (table == "goal_runner_controls") "$row.parent_workflow_id" else "$row.parent_goal_workflow_id"

private fun parentsForWorkflow(workflow: String): String =
  "SELECT progress.parent_workflow_id AS k FROM goal_issue_progress progress " +
    "JOIN feature_task_execution_identities identity " +
    "ON identity.normalized_issue_key = upper(trim(progress.issue_key)) " +
    "WHERE identity.route_scope = 'goal_child' AND identity.workflow_id = $workflow"

private fun standaloneForWorkflow(workflow: String): String =
  "SELECT invocation_id AS k FROM standalone_phase_status WHERE workflow_id = $workflow"

private fun standaloneGroupPeers(row: String): String =
  "SELECT peer.invocation_id AS k FROM standalone_phase_status peer WHERE " +
    standalonePhaseGroupMatch("peer", row)

internal fun standalonePhaseGroupMatch(
  left: String,
  right: String,
): String =
  "$left.repository_identity = $right.repository_identity AND $left.phase_id = $right.phase_id AND " +
    "COALESCE($left.monitor_pr_key, upper($left.issue_key), $left.workflow_id, $left.branch_correlation) = " +
    "COALESCE($right.monitor_pr_key, upper($right.issue_key), $right.workflow_id, $right.branch_correlation)"
