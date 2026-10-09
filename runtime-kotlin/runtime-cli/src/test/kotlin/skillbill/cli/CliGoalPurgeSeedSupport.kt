package skillbill.cli

import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.workflow.taskruntime.artifact.FeatureTaskRuntimeRunEvidenceAddress
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

private val WORKFLOW_KEYED_TABLES =
  listOf(
    "feature_task_workflows",
    "feature_task_execution_identities",
    "feature_task_runtime_worker_leases",
    "goal_run_sessions",
    "goal_subtask_events",
    "feature_task_phase_settlements",
    "worktree_edit_journal",
    "producer_output_evidence",
    "rejected_output_diagnostics",
    "agent_activity_stamps",
  )

private val PARENT_KEYED_TABLES =
  listOf(
    "goal_runner_controls" to "parent_workflow_id",
    "goal_issue_progress" to "parent_workflow_id",
    "goal_planning_preparations" to "parent_goal_workflow_id",
    "goal_shared_preplans" to "parent_goal_workflow_id",
    "goal_subtask_plans" to "parent_goal_workflow_id",
  )

internal data class GoalSeed(
  val issueKey: String,
  val repositoryIdentity: String,
  val parentId: String,
  val childId: String,
  val sessionId: String,
  val parentExists: Boolean = false,
)

internal fun GoalSeed.workflowIds(): List<String> = listOf(parentId, childId)

internal fun ownedRuntimeDirectories(
  repoRoot: Path,
  workflowIds: List<String>,
): List<Path> =
  workflowIds.flatMap { workflowId ->
    listOf(
      repoRoot.resolve(".skill-bill/feature-task-tracking").resolve(
        FeatureTaskRuntimeRunEvidenceAddress.pathSegment(workflowId),
      ),
      repoRoot.resolve(FeatureTaskRuntimeRunEvidenceAddress.workflowStoreRoot(workflowId)),
    )
  }

internal fun seedGoalOwnedState(
  fixture: GoalCliFixture,
  seed: GoalSeed,
  directoryRoot: Path = fixture.tempDir,
) {
  ensureTestDatabase(fixture.dbPath).use { connection ->
    connection.execute("INSERT INTO feature_task_runtime_sessions (session_id) VALUES (?)", seed.sessionId)
    seedGoalWorkflows(connection, seed)
    seedGoalPlanningAndControl(connection, seed)
    seed.workflowIds().forEach { workflowId -> seedWorkflowKeyedRows(connection, seed, workflowId) }
    connection.execute(WORKER_LEASE_SQL, seed.parentId)
  }
  ownedRuntimeDirectories(directoryRoot, seed.workflowIds()).forEach { directory ->
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("seed.txt"), "seeded for ${seed.issueKey}")
  }
}

internal fun ownedRowCounts(
  fixture: GoalCliFixture,
  seed: GoalSeed,
): Map<String, Int> =
  ensureTestDatabase(fixture.dbPath).use { connection ->
    val workflowIds = seed.workflowIds().toTypedArray()
    val placeholders = workflowIds.joinToString(", ") { "?" }
    buildMap {
      WORKFLOW_KEYED_TABLES.forEach { table ->
        put(table, connection.count("SELECT COUNT(*) FROM $table WHERE workflow_id IN ($placeholders)", *workflowIds))
      }
      PARENT_KEYED_TABLES.forEach { (table, column) ->
        put(table, connection.count("SELECT COUNT(*) FROM $table WHERE $column = ?", seed.parentId))
      }
      put(
        "feature_task_runtime_sessions",
        connection.count("SELECT COUNT(*) FROM feature_task_runtime_sessions WHERE session_id = ?", seed.sessionId),
      )
    }
  }

internal fun fixtureParentWorkflowId(fixture: GoalCliFixture): String =
  ensureTestDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement("SELECT workflow_id FROM feature_task_workflows").use { statement ->
      statement.executeQuery().use { rows ->
        check(rows.next()) { "goalFixture must seed a parent workflow." }
        rows.getString(1).also { check(!rows.next()) { "goalFixture must seed exactly one workflow." } }
      }
    }
  }

private fun seedGoalWorkflows(
  connection: Connection,
  seed: GoalSeed,
) {
  if (seed.parentExists) {
    connection.execute(
      "UPDATE feature_task_workflows SET session_id = ? WHERE workflow_id = ?",
      seed.sessionId,
      seed.parentId,
    )
  } else {
    connection.execute(
      """
      INSERT INTO feature_task_workflows (workflow_id, session_id, mode, contract_version, artifacts_json, issue_key)
      VALUES (?, ?, 'runtime', '0.1', ?, ?)
      """.trimIndent(),
      seed.parentId,
      seed.sessionId,
      """{"decomposition_runtime":{"issue_key":"${seed.issueKey}"}}""",
      seed.issueKey,
    )
  }
  connection.execute(
    """
    INSERT INTO feature_task_workflows (workflow_id, session_id, mode, contract_version, artifacts_json, issue_key)
    VALUES (?, ?, 'runtime', '0.1', ?, ?)
    """.trimIndent(),
    seed.childId,
    seed.sessionId,
    "{\"goal_continuation\":{\"issue_key\":\"${seed.issueKey}\",\"subtask_id\":1," +
      "\"parent_workflow_id\":\"${seed.parentId}\"}}",
    seed.issueKey,
  )
  connection.execute(
    """
    INSERT INTO feature_task_execution_identities (
      workflow_id, contract_version, normalized_issue_key, repository_identity, governed_spec_path, mode, route_scope
    ) VALUES (?, '0.1', ?, ?, '.feature-specs/seed/spec_subtask_1.md', 'runtime', 'goal_child')
    """.trimIndent(),
    seed.childId,
    seed.issueKey,
    seed.repositoryIdentity,
  )
}

private fun seedGoalPlanningAndControl(
  connection: Connection,
  seed: GoalSeed,
) {
  connection.execute(
    "INSERT OR REPLACE INTO goal_runner_controls (parent_workflow_id, control_state_json) VALUES (?, ?)",
    seed.parentId,
    """
    {"paused":true,"pause_requested":true,"pause_consumed":true,"pause_reason":"operator_stop",
    "paused_at":"2026-01-01T00:00:00Z","repository_identity":"${seed.repositoryIdentity}"}
    """.trimIndent(),
  )
  connection.execute(
    "INSERT OR REPLACE INTO goal_issue_progress (parent_workflow_id, issue_key) VALUES (?, ?)",
    seed.parentId,
    seed.issueKey,
  )
  connection.execute(PREPARATION_SQL, seed.parentId, seed.issueKey, seed.repositoryIdentity)
  connection.execute(SHARED_PREPLAN_SQL, seed.parentId, seed.issueKey, seed.repositoryIdentity)
  connection.execute(SUBTASK_PLAN_SQL, seed.parentId, seed.issueKey, seed.repositoryIdentity)
}

private fun seedWorkflowKeyedRows(
  connection: Connection,
  seed: GoalSeed,
  workflowId: String,
) {
  listOf(
    """
    INSERT INTO goal_run_sessions (workflow_id, issue_key, started_at)
    VALUES (?1, '${seed.issueKey}', '2026-01-01T00:00:00Z')
    """,
    """
    INSERT INTO goal_subtask_events (
      issue_key, workflow_id, subtask_id, status, started_at, finished_at, duration_ms, attempt_count
    ) VALUES ('${seed.issueKey}', ?1, 1, 'complete', '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', 1000, 1)
    """,
    """
    INSERT INTO feature_task_phase_settlements (workflow_id, phase_id, attempt, kind, envelope_json, recorded_at)
    VALUES (?1, 'plan', 1, 'completed', '{}', '2026-01-01T00:00:00Z')
    """,
    """
    INSERT INTO worktree_edit_journal (workflow_id, recorded_at, path, lines_added, lines_removed, source)
    VALUES (?1, '2026-01-01T00:00:00Z', 'a.kt', 1, 0, 'worktree_probe')
    """,
    """
    INSERT INTO producer_output_evidence (
      workflow_id, phase_id, attempt, agent_id, model, recorded_at, byte_size, sha256, payload
    ) VALUES (?1, 'plan', 1, 'agent', 'model', '2026-01-01T00:00:00Z', 2, 'sha', x'7b7d')
    """,
    """
    INSERT INTO rejected_output_diagnostics (
      identity, workflow_id, phase_id, attempt, rule, rejection_path, reason, agent_id, model, recorded_at,
      byte_size, sha256, lifecycle, payload
    ) VALUES ('diag-' || ?1, ?1, 'plan', 1, 'rule', 'path', 'reason', 'agent', 'model',
      '2026-01-01T00:00:00Z', 2, 'sha', 'stored', x'7b7d')
    """,
    """
    INSERT INTO agent_activity_stamps (workflow_id, recorded_at, label)
    VALUES (?1, '2026-01-01T00:00:00Z', 'stdout')
    """,
  ).forEach { sql -> connection.execute(sql.trimIndent(), workflowId) }
}

private fun Connection.execute(
  sql: String,
  vararg arguments: String,
) {
  prepareStatement(sql).use { statement ->
    arguments.forEachIndexed { index, argument -> statement.setString(index + 1, argument) }
    statement.executeUpdate()
  }
}

private fun Connection.count(
  sql: String,
  vararg arguments: String,
): Int =
  prepareStatement(sql).use { statement ->
    arguments.forEachIndexed { index, argument -> statement.setString(index + 1, argument) }
    statement.executeQuery().use { rows ->
      check(rows.next())
      rows.getInt(1)
    }
  }

private const val WORKER_LEASE_SQL =
  """
  INSERT INTO feature_task_runtime_worker_leases (
    workflow_id, contract_version, generation, owner_token, host_identity, boot_identity,
    pid, process_birth_token, lease_state, heartbeat_at, expires_at, phase_id, phase_attempt
  ) VALUES (?, '0.1', 1, 'owner', 'host', 'boot', 1234, 'birth', 'active',
    '2026-01-01T00:00:00Z', '2000-01-01T00:00:00Z', 'plan', 1)
  """

private const val PREPARATION_SQL =
  """
  INSERT INTO goal_planning_preparations (
    parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
    governed_sub_spec_path, contract_version, parent_spec_hash, sub_spec_hash, decomposition_manifest_hash,
    phase_output_contract_id, phase_output_contract_version, preplan_payload_json, plan_payload_json
  ) VALUES (?, ?, ?, 1, '.feature-specs/seed/subtask.md', '0.1', 'parent', 'subtask',
    'manifest', 'goal-plan', '0.2', '{}', '{}')
  """

private const val SHARED_PREPLAN_SQL =
  """
  INSERT INTO goal_shared_preplans (
    parent_goal_workflow_id, normalized_issue_key, repository_identity, preparation_status,
    contract_version, parent_spec_hash, decomposition_manifest_hash, planning_contract_id,
    planning_contract_version, phase_output_contract_id, phase_output_contract_version,
    payload_sha256, preplan_payload_json
  ) VALUES (?, ?, ?, 'prepared', '0.2', 'parent', 'manifest', 'goal-plan',
    '0.2', 'goal-plan', '0.2', 'sha', '{}')
  """

private const val SUBTASK_PLAN_SQL =
  """
  INSERT INTO goal_subtask_plans (
    parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id, manifest_order,
    governed_sub_spec_path, sub_spec_hash, preparation_status, contract_version, parent_spec_hash,
    decomposition_manifest_hash, planning_contract_id, planning_contract_version,
    phase_output_contract_id, phase_output_contract_version, payload_sha256, plan_payload_json
  ) VALUES (?, ?, ?, 1, 0, '.feature-specs/seed/subtask.md', 'subtask',
    'prepared', '0.2', 'parent', 'manifest', 'goal-plan', '0.2', 'goal-plan', '0.2', 'sha', '{}')
  """
