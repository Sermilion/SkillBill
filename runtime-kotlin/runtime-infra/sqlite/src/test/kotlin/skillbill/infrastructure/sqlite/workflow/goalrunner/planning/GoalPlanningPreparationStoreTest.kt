package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.seedLegacySharedPreplan
import skillbill.infrastructure.sqlite.seedLegacySubtaskPlan
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.goalrunner.model.GoalPlanningPreparationCountResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanListResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoalPlanningPreparationStoreTest {
  @Test
  fun `bounded status covers fresh partial blocked and prepared legacy planning`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      val fresh = store.boundedStatus("goal-1", listOf(1, 2))
      assertEquals(GoalPlanningStatusState.NOT_STARTED, fresh.state)
      assertEquals(1, fresh.currentPlanningSubtaskId)

      connection.seedLegacySharedPreplan(sharedCheckpoint())
      connection.seedLegacySubtaskPlan(planCheckpoint(1, 0))
      val partial = store.boundedStatus("goal-1", listOf(1, 2))
      assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, partial.state)
      assertEquals(1, partial.plannedSubtaskCount)
      assertEquals(2, partial.currentPlanningSubtaskId)

      val blocked = store.boundedStatus("goal-1", listOf(1, 2), 2, "plan agent exhausted")
      assertEquals(GoalPlanningStatusState.BLOCKED, blocked.state)
      assertEquals("plan agent exhausted", blocked.reason)

      connection.seedLegacySubtaskPlan(planCheckpoint(2, 1))
      val prepared = store.boundedStatus("goal-1", listOf(1, 2))
      assertEquals(GoalPlanningStatusState.PREPARED, prepared.state)
      assertNull(prepared.currentPlanningSubtaskId)
      assertNull(prepared.reason)
    }
  }

  @Test
  fun `bounded status reports the capped wave of unplanned subtasks and nothing else`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val ordered = (1..8).toList()

      assertEquals(emptyList<Int>(), store.boundedStatus("goal-1", ordered).planningWaveSubtaskIds)

      connection.seedLegacySharedPreplan(sharedCheckpoint())
      val preplanned = store.boundedStatus("goal-1", ordered)
      assertEquals(GoalPlanningStatusState.PREPLANNED, preplanned.state)
      assertEquals(listOf(1, 2, 3, 4, 5), preplanned.planningWaveSubtaskIds)

      connection.seedLegacySubtaskPlan(planCheckpoint(1, 0))
      assertEquals(listOf(2, 3, 4, 5, 6), store.boundedStatus("goal-1", ordered).planningWaveSubtaskIds)
      val blocked = store.boundedStatus("goal-1", ordered, 4, "plan agent exhausted")
      assertEquals(emptyList<Int>(), blocked.planningWaveSubtaskIds)

      (2..8).forEach { subtaskId -> connection.seedLegacySubtaskPlan(planCheckpoint(subtaskId, subtaskId - 1)) }
      val prepared = store.boundedStatus("goal-1", ordered)
      assertEquals(GoalPlanningStatusState.PREPARED, prepared.state)
      assertEquals(emptyList<Int>(), prepared.planningWaveSubtaskIds)
    }
  }

  @Test
  fun `bounded status reads while another connection holds the writer lock`() {
    val tempDir = Files.createTempDirectory("skillbill-planning-status-contention")
    val dbPath = tempDir.resolve("metrics.db")
    val database =
      sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())
    database.transaction { Unit }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        val status =
          database.read { unitOfWork ->
            unitOfWork.goalPlanningPreparations.boundedStatus("goal-contention", listOf(1, 2))
          }
        assertEquals(GoalPlanningStatusState.NOT_STARTED, status.state)
      } finally {
        writer.createStatement().use { it.execute("ROLLBACK") }
      }
    }
  }

  @Test
  fun `bounded status rejects malformed governed and blocked state`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      assertFailsWith<SkillBillRuntimeException> { store.boundedStatus("goal-1", listOf(1, 1)) }
      assertFailsWith<SkillBillRuntimeException> { store.boundedStatus("goal-1", listOf(1, 2), blockedSubtaskId = 2) }
      assertFailsWith<SkillBillRuntimeException> { store.boundedStatus("goal-1", listOf(1, 2), 3, "unknown subtask") }
    }
  }

  @Test
  fun `legacy plans list count and recover only against complete governed descriptors`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      connection.seedLegacySharedPreplan(sharedCheckpoint())
      connection.seedLegacySubtaskPlan(planCheckpoint(2, 1))
      connection.seedLegacySubtaskPlan(planCheckpoint(1, 0))
      val descriptors = listOf(descriptor(1, 0), descriptor(2, 1), descriptor(3, 2))

      assertEquals(
        listOf(1, 2),
        store.listSubtaskPlansOrdered(identity(), descriptors).foundPlans().map { it.subtaskId },
      )
      assertEquals(GoalPlanningPreparationCountResult.Applied(2), store.preparedPlanCount(identity(), descriptors))

      val conflictedDescriptors =
        descriptors.map { if (it.subtaskId == 2) it.copy(governedSubSpecPath = "wrong.md") else it }
      val conflictedList =
        assertIs<GoalSubtaskPlanListResult.Conflicted>(store.listSubtaskPlansOrdered(identity(), conflictedDescriptors))
      assertEquals(
        GoalPlanningPreparationCountResult.Conflicted(conflictedList.conflict),
        store.preparedPlanCount(identity(), conflictedDescriptors),
      )
    }
  }

  @Test
  fun `malformed legacy row fails loudly after restart`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.seedLegacySharedPreplan(sharedCheckpoint())
      connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE goal_shared_preplans SET payload_sha256 = 'malformed'")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA,
        assertFailsWith<SkillBillRuntimeException> {
          GoalPlanningPreparationStore(connection).findSharedPreplan(identity())
        }.code,
      )
    }
  }

  @Test
  fun `hydrate reads legacy plans after installed runtime schema ids change`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      connection.seedLegacySharedPreplan(sharedCheckpoint())
      connection.seedLegacySubtaskPlan(planCheckpoint(1, 0))
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          UPDATE goal_shared_preplans SET
            planning_contract_id = 'https://installed.example/planning',
            phase_output_contract_id = 'https://installed.example/phase',
            phase_output_contract_version = '0.2'
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          UPDATE goal_subtask_plans SET
            planning_contract_id = 'https://installed.example/planning',
            phase_output_contract_id = 'https://installed.example/phase',
            phase_output_contract_version = '0.2'
          """.trimIndent(),
        )
      }

      val shared = store.findSharedPreplan(identity()).foundCheckpoint()
      val plan = store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath).foundPlan()
      assertEquals("preplan-payload", shared?.preplanPayload)
      assertEquals("plan-1", plan?.planPayload)
      assertEquals("https://installed.example/planning", shared?.provenance?.planningContractId)
      assertEquals("https://installed.example/phase", plan?.provenance?.phaseOutputContractId)
    }
  }

  @Test
  fun `a legacy 0 point 1 paired preparation row is refused instead of read as a normalized plan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      connection.seedLegacyPairedPreparation()

      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          GoalPlanningPreparationStore(connection).findSharedPreplan(identity())
        }
      assertEquals(FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED, failure.code)
    }
  }

  @Test
  fun `hard reset deletion rolls back atomically then removes every legacy table and stays deleted after restart`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      connection.seedLegacySharedPreplan(sharedCheckpoint())
      connection.seedLegacySubtaskPlan(planCheckpoint(1, 0))

      assertFailsWith<IllegalStateException> {
        connection.inNestedWriteTransaction {
          store.deleteByGoal("goal-1")
          error("injected reset manifest failure")
        }
      }
      assertNotNull(store.findSharedPreplan(identity()).foundCheckpoint())
      assertNotNull(store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath).foundPlan())

      assertEquals(2, connection.inNestedWriteTransaction { store.deleteByGoal("goal-1") })
    }
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      assertNull(store.findSharedPreplan(identity()).foundCheckpoint())
      assertEquals(
        GoalPlanningPreparationCountResult.Applied(0),
        store.preparedPlanCount(identity(), listOf(descriptor(1, 0))),
      )
    }
  }
}

private fun Connection.seedLegacyPairedPreparation() {
  prepareStatement(
    """
    INSERT INTO goal_planning_preparations (
      parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
      governed_sub_spec_path, contract_version, parent_spec_hash, sub_spec_hash, decomposition_manifest_hash,
      phase_output_contract_id, phase_output_contract_version, preplan_payload_json, plan_payload_json
    ) VALUES ('goal-1', 'SKILL-128', 'repo-root-realpath-v1:/repository', 1,
      '.feature-specs/SKILL-128/spec_subtask_1.md', '0.1', 'parent', 'subtask', 'manifest',
      'goal-plan', '0.2', '{}', '{}')
    """.trimIndent(),
  ).use { it.executeUpdate() }
}

private fun SharedGoalPreplanLookupResult.foundCheckpoint() =
  assertIs<SharedGoalPreplanLookupResult.Found>(this).checkpoint

private fun GoalSubtaskPlanLookupResult.foundPlan() = assertIs<GoalSubtaskPlanLookupResult.Found>(this).plan

private fun GoalSubtaskPlanListResult.foundPlans() = assertIs<GoalSubtaskPlanListResult.Found>(this).plans
