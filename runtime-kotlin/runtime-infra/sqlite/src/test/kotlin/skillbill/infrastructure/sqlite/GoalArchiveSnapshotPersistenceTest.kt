package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.contracts.goalrunner.GoalRunnerControlPayloadKeys
import skillbill.infrastructure.sqlite.workflow.goalrunner.archive.SqliteGoalArchiveSnapshotPort
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.GoalRunnerControlStore
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.goalrunner.model.GoalArchiveTableRow
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalArchiveSnapshotPersistenceTest {
  @Test
  fun `restore preserves archived goal runner control state for typed repository reads`() {
    val tempDir = Files.createTempDirectory("goal-archive-control-restore")
    val dbPath = tempDir.resolve("metrics.db")
    val factory =
      sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())
    val archive =
      GoalArchiveOwnedTables.of(
        mapOf(
          "goal_runner_controls" to
            listOf(
              GoalArchiveTableRow(
                mapOf(
                  GoalRunnerControlPayloadKeys.PARENT_WORKFLOW_ID to "wftr-archived-control",
                  GoalRunnerControlPayloadKeys.CONTROL_STATE_JSON to
                    JsonCodec.mapToJsonString(
                      mapOf(
                        GoalRunnerControlPayloadKeys.STOP_AFTER_SUBTASK_ID to 7,
                        GoalRunnerControlPayloadKeys.VALIDATION_QUALITY_RETRIES_BY_SUBTASK to mapOf("7" to 3),
                      ),
                    ),
                ),
              ),
            ),
        ),
      )

    SqliteGoalArchiveSnapshotPort(factory).restoreOwnedTables(archive)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val restored = GoalRunnerControlStore(connection).controlState("wftr-archived-control")
      assertEquals(7, restored.stopAfterSubtaskId)
      assertEquals(mapOf(7 to 3), restored.validationQualityRetriesBySubtask)
    }
  }

  @Test
  fun `restore does not transfer an archived active worker lease to destination`() {
    val tempDir = Files.createTempDirectory("goal-archive-worker-lease-restore")
    val dbPath = tempDir.resolve("metrics.db")
    val workflowId = "wftr-destination-lease"
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      store.saveFeatureTaskWorkflow(
        workflowRow(workflowId, "ftr-destination", "bill-feature-task", "plan", FeatureTaskWorkflowMode.RUNTIME),
        FeatureTaskWorkflowMode.RUNTIME,
      )
      assertNull(store.getFeatureTaskRuntimeWorkerOwnership(workflowId))
    }
    val factory =
      sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())
    val archive =
      GoalArchiveOwnedTables.of(
        mapOf("feature_task_runtime_worker_leases" to listOf(activeLeaseArchiveRow(workflowId))),
      )

    SqliteGoalArchiveSnapshotPort(factory).restoreOwnedTables(archive)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      assertNull(store.getFeatureTaskRuntimeWorkerOwnership(workflowId))
      val leaseCount =
        connection.createStatement().use { statement ->
          statement.executeQuery("SELECT COUNT(*) FROM feature_task_runtime_worker_leases").use { rows ->
            rows.next()
            rows.getInt(1)
          }
        }
      assertEquals(0, leaseCount)
    }
  }

  @Test
  fun `restore assigns local journal ids and preserves archived id order`() {
    val tempDir = Files.createTempDirectory("goal-archive-journal-restore")
    val dbPath = tempDir.resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      insertJournalRow(connection, 1, "wftr-destination", "destination.txt", 9 to 4)
    }
    val factory =
      sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())
    val archive =
      GoalArchiveOwnedTables.of(
        mapOf(
          "worktree_edit_journal" to
            listOf(
              journalArchiveRow(2, "wftr-imported", "second.txt", 2 to 1),
              journalArchiveRow(1, "wftr-imported", "first.txt", 5 to 3),
            ),
        ),
      )

    SqliteGoalArchiveSnapshotPort(factory).restoreOwnedTables(archive)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        listOf(
          JournalRow(
            1,
            "wftr-destination",
            "implement",
            "2026-01-01T00:00:00Z",
            "destination.txt",
            9,
            4,
            "worktree_probe",
          ),
          JournalRow(2, "wftr-imported", "implement", "2026-01-01T00:00:00Z", "first.txt", 5, 3, "worktree_probe"),
          JournalRow(3, "wftr-imported", "implement", "2026-01-01T00:00:00Z", "second.txt", 2, 1, "worktree_probe"),
        ),
        readJournalRows(connection),
      )
    }
  }

  @Test
  fun `failed journal restore rolls back earlier inserted rows`() {
    val tempDir = Files.createTempDirectory("goal-archive-journal-rollback")
    val dbPath = tempDir.resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      insertJournalRow(connection, 1, "wftr-destination", "destination.txt", 9 to 4)
    }
    val factory =
      sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())
    val archive =
      GoalArchiveOwnedTables.of(
        mapOf(
          "worktree_edit_journal" to
            listOf(
              journalArchiveRow(2, "wftr-imported", "would-be-partial.txt", 2 to 1),
              journalArchiveRow(3, "wftr-imported", "invalid.txt", 1 to 0, source = "invalid"),
            ),
        ),
      )

    assertFails { SqliteGoalArchiveSnapshotPort(factory).restoreOwnedTables(archive) }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        listOf(
          JournalRow(
            1,
            "wftr-destination",
            "implement",
            "2026-01-01T00:00:00Z",
            "destination.txt",
            9,
            4,
            "worktree_probe",
          ),
        ),
        readJournalRows(connection),
      )
    }
  }

  @Test
  fun `snapshot returns purge-owned planning and evidence rows for every counted table`() {
    val tempDir = Files.createTempDirectory("goal-archive-snapshot")
    val dbPath = tempDir.resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val parentId = "wftr-parent-archive"
      val childId = "wftr-child-archive"
      store.saveFeatureTaskWorkflow(
        workflowRow(parentId, "ftr-parent", "bill-feature-task", "plan", FeatureTaskWorkflowMode.RUNTIME).copy(
          issueKey = "SKILL-474",
          artifactsJson = """{"decomposition_runtime":{"issue_key":"SKILL-474"}}""",
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
      store.saveFeatureTaskWorkflow(goalChildWorkflow(childId, parentId), FeatureTaskWorkflowMode.RUNTIME)
      val planId = "wftr-plan-archive"
      val planBundleKey = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.label()
      store.saveFeatureTaskWorkflow(
        workflowRow(planId, "ftr-plan", "bill-feature-task", "plan_subtasks", FeatureTaskWorkflowMode.RUNTIME).copy(
          issueKey = "SKILL-474",
          currentStepId = "plan_subtasks",
          artifactsJson =
            """{"$planBundleKey":{"origin":"existing","parent_spec_sha256":"parent",""" +
              """"manifest_sha256":"manifest","subtasks":[]}}""",
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
      seedPlanningAndEvidence(connection, parentId, childId)
      val factory =
        sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())
      val target = GoalPurgeTarget(setOf(parentId), setOf(parentId, childId, planId))
      val expectedTables =
        factory.read { unitOfWork -> unitOfWork.countDecomposedGoalState(target).byTable.keys }
      val snapshot = SqliteGoalArchiveSnapshotPort(factory).snapshot(target)
      assertEquals(3, snapshot.workflows.size)
      assertEquals(1, snapshot.sharedPreplans.size)
      assertTrue(
        snapshot.workflows.any { workflow -> planBundleKey in workflow.artifactsJson },
        "Expected FEATURE_TASK_RUNTIME_PLAN_BUNDLE in snapshot workflow artifactsJson.",
      )
      assertTrue(
        snapshot.ownedTables.rows("producer_output_evidence")?.isNotEmpty() == true,
        "Expected producer_output_evidence rows in snapshot.",
      )
      expectedTables.forEach { table ->
        when (table) {
          "feature_task_workflows" -> assertEquals(3, snapshot.workflows.size)
          "goal_shared_preplans" -> assertEquals(1, snapshot.sharedPreplans.size)
          "goal_subtask_plans" -> assertEquals(1, snapshot.subtaskPlans.size)
          "goal_planning" ->
            assertTrue(
              snapshot.legacyPreparations.isNotEmpty(),
              "Expected goal_planning_preparations rows in snapshot.",
            )
          else ->
            assertNotNull(snapshot.ownedTables.rows(table), "Missing owned table '$table' in snapshot.")
        }
      }
    }
  }

  private fun insertJournalRow(
    connection: Connection,
    id: Long,
    workflowId: String,
    path: String,
    linesChanged: Pair<Int, Int>,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO worktree_edit_journal (
        id, workflow_id, phase_id, recorded_at, path, lines_added, lines_removed, source
      ) VALUES (?, ?, 'implement', '2026-01-01T00:00:00Z', ?, ?, ?, 'worktree_probe')
      """.trimIndent(),
    ).use { statement ->
      statement.setLong(1, id)
      statement.setString(2, workflowId)
      statement.setString(3, path)
      statement.setInt(4, linesChanged.first)
      statement.setInt(5, linesChanged.second)
      statement.executeUpdate()
    }
  }

  private fun readJournalRows(connection: Connection): List<JournalRow> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT id, workflow_id, phase_id, recorded_at, path, lines_added, lines_removed, source " +
          "FROM worktree_edit_journal ORDER BY id",
      ).use { rows ->
        buildList {
          while (rows.next()) {
            add(
              JournalRow(
                rows.getLong("id"),
                rows.getString("workflow_id"),
                rows.getString("phase_id"),
                rows.getString("recorded_at"),
                rows.getString("path"),
                rows.getInt("lines_added"),
                rows.getInt("lines_removed"),
                rows.getString("source"),
              ),
            )
          }
        }
      }
    }

  private fun journalArchiveRow(
    id: Long,
    workflowId: String,
    path: String,
    linesChanged: Pair<Int, Int>,
    source: String = "worktree_probe",
  ): GoalArchiveTableRow =
    GoalArchiveTableRow(
      linkedMapOf(
        "id" to id,
        "workflow_id" to workflowId,
        "phase_id" to "implement",
        "recorded_at" to "2026-01-01T00:00:00Z",
        "path" to path,
        "lines_added" to linesChanged.first,
        "lines_removed" to linesChanged.second,
        "source" to source,
      ),
    )

  private fun activeLeaseArchiveRow(workflowId: String): GoalArchiveTableRow =
    GoalArchiveTableRow(
      linkedMapOf(
        "workflow_id" to workflowId,
        "contract_version" to "0.1",
        "generation" to 1,
        "owner_token" to "archived-owner",
        "host_identity" to "archived-host",
        "boot_identity" to "archived-boot",
        "pid" to 1234,
        "process_birth_token" to "archived-process",
        "lease_state" to "active",
        "heartbeat_at" to "2026-01-01T00:00:00Z",
        "expires_at" to "2026-01-01T00:01:00Z",
        "phase_id" to "plan",
        "phase_attempt" to 1,
      ),
    )

  private fun seedPlanningAndEvidence(
    connection: Connection,
    parentId: String,
    childId: String,
  ) {
    insertBoundId(connection, SHARED_PREPLAN_INSERT, parentId)
    insertBoundId(connection, SUBTASK_PLAN_INSERT, parentId)
    insertBoundId(connection, LEGACY_PREPARATION_INSERT, parentId)
    insertBoundId(connection, PRODUCER_EVIDENCE_INSERT, childId)
    insertBoundId(connection, RUNNER_CONTROLS_INSERT, parentId)
    insertBoundId(connection, ISSUE_PROGRESS_INSERT, parentId)
  }

  private fun insertBoundId(
    connection: Connection,
    sql: String,
    id: String,
  ) {
    connection.prepareStatement(sql).use { statement ->
      statement.setString(1, id)
      statement.executeUpdate()
    }
  }
}

private data class JournalRow(
  val id: Long,
  val workflowId: String,
  val phaseId: String,
  val recordedAt: String,
  val path: String,
  val linesAdded: Int,
  val linesRemoved: Int,
  val source: String,
)

private const val SHARED_PREPLAN_INSERT =
  """
  INSERT INTO goal_shared_preplans (
    parent_goal_workflow_id, normalized_issue_key, repository_identity, preparation_status,
    contract_version, parent_spec_hash, decomposition_manifest_hash, planning_contract_id,
    planning_contract_version, phase_output_contract_id, phase_output_contract_version,
    payload_sha256, preplan_payload_json
  ) VALUES (?, 'SKILL-474', 'repo', 'prepared', '0.2', 'parent', 'manifest', 'goal-plan',
    '0.2', 'goal-plan', '0.2', 'sha', '{}')
  """

private const val SUBTASK_PLAN_INSERT =
  """
  INSERT INTO goal_subtask_plans (
    parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id, manifest_order,
    governed_sub_spec_path, sub_spec_hash, preparation_status, contract_version, parent_spec_hash,
    decomposition_manifest_hash, planning_contract_id, planning_contract_version,
    phase_output_contract_id, phase_output_contract_version, payload_sha256, plan_payload_json
  ) VALUES (?, 'SKILL-474', 'repo', 1, 0, 'spec.md', 'subtask', 'prepared', '0.2', 'parent',
    'manifest', 'goal-plan', '0.2', 'goal-plan', '0.2', 'sha', '{}')
  """

private const val LEGACY_PREPARATION_INSERT =
  """
  INSERT INTO goal_planning_preparations (
    parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
    governed_sub_spec_path, contract_version, parent_spec_hash, sub_spec_hash, decomposition_manifest_hash,
    phase_output_contract_id, phase_output_contract_version, preplan_payload_json, plan_payload_json
  ) VALUES (?, 'SKILL-474', 'repo', 1, 'spec.md', '0.1', 'parent', 'subtask', 'manifest',
    'goal-plan', '0.2', '{}', '{}')
  """

private const val PRODUCER_EVIDENCE_INSERT =
  """
  INSERT INTO producer_output_evidence (
    workflow_id, phase_id, attempt, agent_id, model, recorded_at, byte_size, sha256, payload
  ) VALUES (?, 'plan', 1, 'agent', 'model', '2026-01-01T00:00:00Z', 2, 'sha', x'7b7d')
  """

private const val RUNNER_CONTROLS_INSERT =
  """
  INSERT INTO goal_runner_controls (parent_workflow_id, control_state_json)
  VALUES (?, '{}')
  """

private const val ISSUE_PROGRESS_INSERT =
  """
  INSERT INTO goal_issue_progress (parent_workflow_id, issue_key)
  VALUES (?, 'SKILL-474')
  """
