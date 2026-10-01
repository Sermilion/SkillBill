package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.infrastructure.sqlite.GoalPlanningPreparationStore
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.workflow.goalrunner.shared.INVALIDATED_SHARED_PREPLAN_PAYLOAD
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalPlanningPreparationStoreTest {
  @Test
  fun `bounded status covers fresh partial blocked and prepared planning`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      val fresh = store.boundedStatus("goal-1", listOf(1, 2))
      assertEquals(GoalPlanningStatusState.NOT_STARTED, fresh.state)
      assertEquals(1, fresh.currentPlanningSubtaskId)

      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      val partial = store.boundedStatus("goal-1", listOf(1, 2))
      assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, partial.state)
      assertEquals(1, partial.plannedSubtaskCount)
      assertEquals(2, partial.currentPlanningSubtaskId)

      val blocked = store.boundedStatus("goal-1", listOf(1, 2), 2, "plan agent exhausted")
      assertEquals(GoalPlanningStatusState.BLOCKED, blocked.state)
      assertEquals("plan agent exhausted", blocked.reason)
      assertEquals(2, blocked.currentPlanningSubtaskId)

      store.checkpointSubtaskPlan(planCheckpoint(2, 1))
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

      val notStarted = store.boundedStatus("goal-1", ordered)
      assertEquals(emptyList<Int>(), notStarted.planningWaveSubtaskIds)

      store.checkpointSharedPreplan(sharedCheckpoint())
      val preplanned = store.boundedStatus("goal-1", ordered)
      assertEquals(GoalPlanningStatusState.PREPLANNED, preplanned.state)
      assertEquals(listOf(1, 2, 3, 4, 5), preplanned.planningWaveSubtaskIds)
      assertEquals(1, preplanned.currentPlanningSubtaskId)

      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      val partial = store.boundedStatus("goal-1", ordered)
      assertEquals(listOf(2, 3, 4, 5, 6), partial.planningWaveSubtaskIds)
      assertEquals(2, partial.currentPlanningSubtaskId)

      val blocked = store.boundedStatus("goal-1", ordered, 4, "plan agent exhausted")
      assertEquals(emptyList<Int>(), blocked.planningWaveSubtaskIds)
      assertEquals(4, blocked.currentPlanningSubtaskId)

      (2..8).forEachIndexed { index, subtaskId -> store.checkpointSubtaskPlan(planCheckpoint(subtaskId, index + 1)) }
      val prepared = store.boundedStatus("goal-1", ordered)
      assertEquals(GoalPlanningStatusState.PREPARED, prepared.state)
      assertEquals(emptyList<Int>(), prepared.planningWaveSubtaskIds)
    }
  }

  @Test
  fun `non-ascending manifest order keeps the single id and resume reason at the wave minimum`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())

      val status = store.boundedStatus("goal-1", listOf(3, 1, 2))

      assertEquals(listOf(3, 1, 2), status.planningWaveSubtaskIds)
      assertEquals(1, status.currentPlanningSubtaskId)
      assertEquals("Shared preplan is saved; planning can resume at subtask 1.", status.reason)
    }
  }

  @Test
  fun `bounded status reads while another connection holds the writer lock`() {
    val tempDir = Files.createTempDirectory("skillbill-planning-status-contention")
    val dbPath = tempDir.resolve("metrics.db")
    val database =
      sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())
    database.read { Unit }

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

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        store.boundedStatus("goal-1", listOf(1, 1))
      }
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        store.boundedStatus("goal-1", listOf(1, 2), blockedSubtaskId = 2)
      }
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        store.boundedStatus("goal-1", listOf(1, 2), 3, "unknown subtask")
      }
    }
  }

  @Test
  fun `normalized checkpoints list count and recover only against complete governed descriptors`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      val descriptors = listOf(descriptor(1, 0), descriptor(2, 1), descriptor(3, 2))

      assertEquals(listOf(1, 2), store.listSubtaskPlansOrdered(identity(), descriptors).map { it.subtaskId })
      assertEquals(2, store.preparedPlanCount(identity(), descriptors))

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.listSubtaskPlansOrdered(
          identity(),
          descriptors.map { if (it.subtaskId == 2) it.copy(governedSubSpecPath = "wrong.md") else it },
        )
      }
    }
  }

  @Test
  fun `normalized checkpoints survive restart and incompatible soft-reset provenance fails loudly`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      assertNotNull(store.findSharedPreplan(identity()))
      val descriptors = listOf(descriptor(1, 0), descriptor(2, 1))
      assertEquals(1, store.preparedPlanCount(identity(), descriptors))
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.checkpointSharedPreplan(
          sharedCheckpoint().copy(provenance = provenance().copy(parentSpecHash = "f".repeat(64))),
        )
      }
    }
  }

  @Test
  fun `normalized hard-reset deletion rolls back atomically and remains deleted after restart`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))

      assertFailsWith<IllegalStateException> {
        connection.inNestedWriteTransaction {
          store.deleteByGoal("goal-1")
          error("injected reset manifest failure")
        }
      }
      assertNotNull(store.findSharedPreplan(identity()))
      assertNotNull(store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath))

      connection.inNestedWriteTransaction { store.deleteByGoal("goal-1") }
    }
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      assertNull(store.findSharedPreplan(identity()))
      assertEquals(0, store.preparedPlanCount(identity(), listOf(descriptor(1, 0))))
    }
  }

  @Test
  fun `normalized immutable replay conflict is rejected without changing the stored plan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = planCheckpoint(1, 0)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(original)

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.checkpointSubtaskPlan(
          original.copy(payloadSha256 = "f".repeat(64), planPayload = "changed-plan"),
        )
      }
      assertEquals(
        original.planPayload,
        store.findSubtaskPlan(identity(), 1, original.governedSubSpecPath)?.planPayload,
      )
    }
  }

  @Test
  fun `normalized alternate uniqueness conflicts fail loudly and preserve the original plan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = planCheckpoint(1, 0)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(original)

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.checkpointSubtaskPlan(
          planCheckpoint(2, 1).copy(governedSubSpecPath = original.governedSubSpecPath),
        )
      }
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.checkpointSubtaskPlan(planCheckpoint(2, 0))
      }
      assertEquals(1, store.preparedPlanCount(identity(), listOf(descriptor(1, 0))))
    }
  }

  @Test
  fun `malformed normalized row fails loudly after restart`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE goal_shared_preplans SET payload_sha256 = 'malformed'")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningPreparationStore(connection).findSharedPreplan(identity())
      }
    }
  }

  @Test
  fun `hydrate reuses shared preplan and plan after installed runtime schema ids change`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
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

      val shared = store.findSharedPreplan(identity())
      val plan = store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)
      assertEquals("preplan-payload", shared?.preplanPayload)
      assertEquals("plan-1", plan?.planPayload)
      assertEquals("https://installed.example/planning", shared?.provenance?.planningContractId)
      assertEquals("https://installed.example/phase", plan?.provenance?.phaseOutputContractId)
      assertEquals("0.2", plan?.provenance?.phaseOutputContractVersion)
    }
  }

  @Test
  fun `mark prepared stores and recovers a single subtask pair`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)

      store.markPrepared(record)

      val recovered = store.preparationRecord.findByGoalAndSubtask("goal-1", 1)
      assertEquals(record.copy(createdAt = recovered!!.createdAt, updatedAt = recovered.updatedAt), recovered)
      assertEquals(listOf(1), store.preparationRecord.listPreparedByGoalOrdered("goal-1").map { it.subtaskId })
      assertEquals(1, store.preparationRecord.preparedCount("goal-1"))
      assertEquals(2, store.preparationRecord.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2, 3)))
      val status = store.preparationRecord.preparedStatus("goal-1", 1)
      assertEquals(GoalPlanningPreparationState.PREPARED, status?.preparationStatus)
      assertEquals(record.provenance, status?.provenance)
    }
  }

  @Test
  fun `marking the same pair again is an idempotent no-op`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)

      store.markPrepared(record)
      store.markPrepared(record)

      assertEquals(1, store.preparationRecord.preparedCount("goal-1"))
      val recovered = store.preparationRecord.findByGoalAndSubtask("goal-1", 1)
      assertEquals(record.preplanPayload, recovered?.preplanPayload)
    }
  }

  @Test
  fun `delete by goal removes only the selected parent preparation`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1))
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 2))
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-2", subtaskId = 1))

      assertEquals(2, store.deleteByGoal("goal-1"))

      assertEquals(0, store.preparationRecord.preparedCount("goal-1"))
      assertEquals(1, store.preparationRecord.preparedCount("goal-2"))
    }
  }

  @Test
  fun `marking a differing provenance pair fails loudly and leaves the stored pair unchanged`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1, subSpecHash = "sub-spec-A")
      store.markPrepared(original)

      val conflicting =
        original.copy(
          provenance = original.provenance.copy(subSpecHash = "sub-spec-B"),
          planPayload = """{"phase_id":"plan","v":2}""",
        )

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> { store.markPrepared(conflicting) }

      val recovered = store.preparationRecord.findByGoalAndSubtask("goal-1", 1)
      assertEquals(original.provenance.subSpecHash, recovered?.provenance?.subSpecHash)
      assertEquals(original.planPayload, recovered?.planPayload)
    }
  }

  @Test
  fun `marking a same-key pair with a diverging repository identity fails loudly`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original =
        preparationRecord(
          parentGoalWorkflowId = "goal-1",
          subtaskId = 1,
          repositoryIdentity = "repo-root-realpath-v1:/repo-a",
        )
      store.markPrepared(original)

      val conflicting = original.copy(repositoryIdentity = "repo-root-realpath-v1:/repo-b")

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> { store.markPrepared(conflicting) }

      val recovered = store.preparationRecord.findByGoalAndSubtask("goal-1", 1)
      assertEquals(original.repositoryIdentity, recovered?.repositoryIdentity)
      assertEquals(original.provenance, recovered?.provenance)
    }
  }

  @Test
  fun `marking a same-key pair with a diverging normalized issue key fails loudly`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
      store.markPrepared(original)

      val conflicting = original.copy(normalizedIssueKey = "SKILL-999")

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> { store.markPrepared(conflicting) }

      val recovered = store.preparationRecord.findByGoalAndSubtask("goal-1", 1)
      assertEquals(original.normalizedIssueKey, recovered?.normalizedIssueKey)
      assertEquals(original.provenance, recovered?.provenance)
    }
  }

  @Test
  fun `prepared pairs are isolated across parent goals`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1))
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-2", subtaskId = 1))

      assertEquals(1, store.preparationRecord.preparedCount("goal-1"))
      assertEquals(1, store.preparationRecord.preparedCount("goal-2"))
      assertEquals(2, store.preparationRecord.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2)))
      assertNull(store.preparationRecord.findByGoalAndSubtask("goal-1", 2))
    }
  }

  @Test
  fun `prepared pairs persist repository identity for repo-scoped recovery`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.markPrepared(
        preparationRecord(
          parentGoalWorkflowId = "goal-1",
          subtaskId = 1,
          repositoryIdentity = "repo-root-realpath-v1:/repo-a",
        ),
      )

      val recovered = store.preparationRecord.findByGoalAndSubtask("goal-1", 1)
      assertEquals("repo-root-realpath-v1:/repo-a", recovered?.repositoryIdentity)
    }
  }

  @Test
  fun `ordered listing orders by subtask id and first missing walks ordered ids`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 3))
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1))

      assertEquals(listOf(1, 3), store.preparationRecord.listPreparedByGoalOrdered("goal-1").map { it.subtaskId })
      assertEquals(2, store.preparationRecord.preparedCount("goal-1"))
      assertEquals(2, store.preparationRecord.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2, 3, 4)))
    }
  }

  @Test
  fun `first missing or incomplete returns null when every ordered id is prepared`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      listOf(1, 2, 3).forEach { subtaskId ->
        store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = subtaskId))
      }

      assertNull(store.preparationRecord.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2, 3)))
      assertNull(store.preparationRecord.firstMissingOrIncompleteSubtask("goal-1", emptyList()))
    }
  }

  @Test
  fun `restart recovery reopens the database and recovers the first incomplete subtask`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      GoalPlanningPreparationStore(connection).markPrepared(
        preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1),
      )
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      assertEquals(1, store.preparationRecord.preparedCount("goal-1"))
      assertEquals(2, store.preparationRecord.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2, 3)))
      assertNotNull(store.preparationRecord.findByGoalAndSubtask("goal-1", 1))
    }
  }

  @Test
  fun `malformed envelope with wrong contract version is rejected`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(contractVersion = "0.2")

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(record) }
      assertNull(store.preparationRecord.findByGoalAndSubtask("goal-1", 1))
    }
  }
}

class GoalPlanningPreparationStoreMutationTest {
  @Test
  fun `malformed envelope with non-positive subtask id is rejected`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 0)

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(record) }
    }
  }

  @Test
  fun `malformed envelope with pending status is rejected at the checkpoint seam`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val pending =
        preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
          .copy(preparationStatus = GoalPlanningPreparationState.PENDING)

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(pending) }
    }
  }

  @Test
  fun `malformed envelope missing provenance hashes is rejected`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record =
        preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
          provenance =
            GoalPlanningPreparationProvenance(
              parentSpecHash = "",
              subSpecHash = "sub",
              decompositionManifestHash = "manifest",
            ),
        )

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(record) }
    }
  }

  @Test
  fun `malformed envelope with a divergent phase output contract version is rejected at the store seam`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val base = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
      val record =
        base.copy(
          provenance = base.provenance.copy(phaseOutputContractVersion = "9.9"),
        )

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(record) }
      assertNull(store.preparationRecord.findByGoalAndSubtask("goal-1", 1))
    }
  }

  @Test
  fun `prepared status returns null for an unknown subtask`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      assertNull(store.preparationRecord.preparedStatus("goal-1", 1))
    }
  }

  @Test
  fun `replacing a shared preplan overwrites the payload and discards listed plans for provenance safety`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))

      store.replaceSharedPreplan(
        sharedCheckpoint().copy(payloadSha256 = "9".repeat(64), preplanPayload = "regenerated-preplan"),
        sharedCheckpoint().payloadSha256,
        cascadePlanSubtaskIds = listOf(1),
      )

      assertEquals("regenerated-preplan", store.findSharedPreplan(identity())?.preplanPayload)
      assertNull(
        store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath),
        "listed cascade ids must be discarded when their governing shared preplan is replaced",
      )
    }
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        "regenerated-preplan",
        GoalPlanningPreparationStore(connection).findSharedPreplan(identity())?.preplanPayload,
      )
    }
  }

  @Test
  fun `replacing a shared preplan with no stored row fails the compare and replace`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.replaceSharedPreplan(sharedCheckpoint(), sharedCheckpoint().payloadSha256)
      }

      assertNull(store.findSharedPreplan(identity()))
    }
  }

  @Test
  fun `replacing a shared preplan rejects a stale observed payload without discarding plans`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.replaceSharedPreplan(
          sharedCheckpoint().copy(payloadSha256 = "9".repeat(64), preplanPayload = "regenerated-preplan"),
          "8".repeat(64),
        )
      }

      assertEquals("preplan-payload", store.findSharedPreplan(identity())?.preplanPayload)
      assertEquals(
        "plan-1",
        store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)?.planPayload,
      )
    }
  }

  @Test
  fun `deleting a subtask plan removes only that row and leaves siblings and shared preplan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))
      store.checkpointSubtaskPlan(planCheckpoint(3, 2))
      val descriptors = listOf(descriptor(1, 0), descriptor(2, 1), descriptor(3, 2))

      assertEquals(1, store.deleteSubtaskPlan("goal-1", 3))
      assertNull(store.findSubtaskPlan(identity(), 3, descriptor(3, 2).governedSubSpecPath))
      assertEquals(listOf(1, 2), store.listSubtaskPlansOrdered(identity(), descriptors).map { it.subtaskId })
      assertNotNull(store.findSharedPreplan(identity()))
      assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, store.boundedStatus("goal-1", listOf(1, 2, 3)).state)
      assertEquals(2, store.boundedStatus("goal-1", listOf(1, 2, 3)).plannedSubtaskCount)
      assertEquals(3, store.boundedStatus("goal-1", listOf(1, 2, 3)).currentPlanningSubtaskId)
      assertEquals(
        "Saved plans will be reused; planning can resume at subtask 3.",
        store.boundedStatus("goal-1", listOf(1, 2, 3)).reason,
      )
      assertEquals(0, store.deleteSubtaskPlan("goal-1", 3))
    }
  }

  @Test
  fun `deleting a shared preplan on digest match removes shared and cascades every plan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))
      store.checkpointSubtaskPlan(planCheckpoint(3, 2))
      val descriptors = listOf(descriptor(1, 0), descriptor(2, 1), descriptor(3, 2))

      assertEquals(1, store.deleteSharedPreplan(identity(), sharedCheckpoint().payloadSha256))
      assertNull(store.findSharedPreplan(identity()))
      assertNull(store.sharedPreplanPayloadSha256("goal-1"))
      assertEquals(emptyList(), store.listSubtaskPlansOrdered(identity(), descriptors))
      assertEquals(GoalPlanningStatusState.NOT_STARTED, store.boundedStatus("goal-1", listOf(1, 2, 3)).state)
      assertTrue(!store.boundedStatus("goal-1", listOf(1, 2, 3)).sharedPreplanPrepared)
      assertEquals(
        "Goal planning has not started.",
        store.boundedStatus("goal-1", listOf(1, 2, 3)).reason,
      )
    }
  }

  @Test
  fun `deleting a shared preplan on digest mismatch refuses with zero mutation`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.deleteSharedPreplan(identity(), "8".repeat(64))
      }

      assertEquals("preplan-payload", store.findSharedPreplan(identity())?.preplanPayload)
      assertEquals(sharedCheckpoint().payloadSha256, store.sharedPreplanPayloadSha256("goal-1"))
      assertEquals(
        "plan-1",
        store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)?.planPayload,
      )
      assertEquals(
        "plan-2",
        store.findSubtaskPlan(identity(), 2, descriptor(2, 1).governedSubSpecPath)?.planPayload,
      )
    }
  }

  @Test
  fun `advancing shared preplan provenance keeps payload bytes and restamps plan provenance`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))
      val advanced = provenance().copy(parentSpecHash = "d".repeat(64))

      store.advanceSharedPreplanProvenance(identity(), sharedCheckpoint().payloadSha256, advanced)

      val shared = requireNotNull(store.findSharedPreplan(identity()))
      assertEquals("preplan-payload", shared.preplanPayload)
      assertEquals(sharedCheckpoint().payloadSha256, shared.payloadSha256)
      assertEquals(advanced, shared.provenance)
      assertEquals(advanced, store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)?.provenance)
      assertEquals(advanced, store.findSubtaskPlan(identity(), 2, descriptor(2, 1).governedSubSpecPath)?.provenance)
      assertEquals("plan-1", store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)?.planPayload)
    }
  }

  @Test
  fun `cascadeSiblingPlansAfterSharedPreplanRefresh discards only listed plan ids`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))

      assertEquals(listOf(2), store.cascadeSiblingPlansAfterSharedPreplanRefresh("goal-1", listOf(2)))

      assertEquals("preplan-payload", store.findSharedPreplan(identity())?.preplanPayload)
      assertEquals(
        "plan-1",
        store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)?.planPayload,
      )
      assertNull(store.findSubtaskPlan(identity(), 2, descriptor(2, 1).governedSubSpecPath))
    }
  }

  @Test
  fun `replacing a shared preplan cascades listed ids and restamps survivors`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))
      val replacement =
        sharedCheckpoint().copy(
          payloadSha256 = "9".repeat(64),
          preplanPayload = "regenerated-preplan",
          provenance = provenance().copy(parentSpecHash = "d".repeat(64)),
        )

      store.replaceSharedPreplan(replacement, sharedCheckpoint().payloadSha256, cascadePlanSubtaskIds = listOf(2))

      assertEquals("regenerated-preplan", store.findSharedPreplan(identity())?.preplanPayload)
      assertNull(store.findSubtaskPlan(identity(), 2, descriptor(2, 1).governedSubSpecPath))
      val survivor = requireNotNull(store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath))
      assertEquals("plan-1", survivor.planPayload)
      assertEquals(replacement.provenance, survivor.provenance)
    }
  }

  @Test
  fun `invalidating shared preplan keeps survivors and reports not prepared`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))

      assertEquals(1, store.invalidateSharedPreplan(identity(), sharedCheckpoint().payloadSha256))
      assertTrue(!store.hasPreparedSharedPreplan("goal-1"))
      assertNull(store.sharedPreplanPayloadSha256("goal-1"))
      assertEquals(
        "plan-1",
        store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)?.planPayload,
      )
      assertEquals(
        "plan-2",
        store.findSubtaskPlan(identity(), 2, descriptor(2, 1).governedSubSpecPath)?.planPayload,
      )

      assertEquals(
        INVALIDATED_SHARED_PREPLAN_PAYLOAD,
        store.findSharedPreplan(identity())?.preplanPayload,
      )
    }
  }

  @Test
  fun `replacing a subtask plan overwrites the payload and leaves its siblings alone`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))

      store.replaceSubtaskPlan(
        planCheckpoint(1, 0).copy(payloadSha256 = "9".repeat(64), planPayload = "regenerated-plan"),
      )

      assertEquals(
        "regenerated-plan",
        store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)?.planPayload,
      )
      assertEquals("plan-2", store.findSubtaskPlan(identity(), 2, descriptor(2, 1).governedSubSpecPath)?.planPayload)
    }
  }

  @Test
  fun `replacing a subtask plan still enforces provenance parity with the governing shared preplan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      val drifted =
        planCheckpoint(1, 0).copy(
          provenance = provenance().copy(parentSpecHash = "f".repeat(64)),
          planPayload = "regenerated-plan",
        )

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> { store.replaceSubtaskPlan(drifted) }
      assertEquals(
        "plan-1",
        store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath)?.planPayload,
      )
    }
  }

  @Test
  fun `replacing a subtask plan before its shared preplan is checkpointed fails loudly`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.replaceSubtaskPlan(planCheckpoint(1, 0)) }
    }
  }
}
