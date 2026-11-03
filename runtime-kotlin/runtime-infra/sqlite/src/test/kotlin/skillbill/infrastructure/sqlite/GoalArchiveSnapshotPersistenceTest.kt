package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.workflow.goalrunner.archive.SqliteGoalArchiveSnapshotPort
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalArchiveSnapshotPersistenceTest {
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
