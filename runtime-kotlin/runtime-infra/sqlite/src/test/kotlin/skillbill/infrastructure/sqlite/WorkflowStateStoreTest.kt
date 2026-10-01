package skillbill.infrastructure.sqlite

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.error.shellcontent.ProseFeatureTaskWorkflowWriteRefusedError
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.workflow.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.workflow.FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.workflow.WorkflowStateStore
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode.PROSE
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowStateStoreTest {
  @Test
  fun `malformed goal continuation is rejected before sqlite persistence`() {
    val dbPath = Files.createTempDirectory("malformed-goal-continuation-write").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val validator =
        object : WorkflowSnapshotValidator {
          override fun validate(
            snapshot: WorkflowStateSnapshot,
            slug: String,
          ) {
            if (DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.contains(snapshot.artifacts)) {
              snapshot.artifacts.goalContinuationArtifact()
            }
          }
        }
      val store = WorkflowStateStore(connection, Clock.systemUTC(), validator)
      val row =
        workflowRow(
          "wftr-malformed-continuation",
          "ftr-malformed-continuation",
          "bill-feature-task",
          "plan",
          RUNTIME,
        ).copy(
          artifactsJson =
            """{"${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()}":{""" +
              """"issue_key":"SKILL-372","subtask_id":2.7,"suppress_pr":true,""" +
              """"goal_branch":"feat/SKILL-372","code_review_mode":"inline"}}""",
        )

      assertFailsWith<InvalidWorkflowStateSchemaError> {
        store.saveFeatureTaskWorkflow(row, RUNTIME)
      }
      assertEquals(null, store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME))
    }
  }

  @Test
  fun `identity-less goal parent is excluded from standalone candidates so lookup can reach goal continuation`() {
    val dbPath = Files.createTempDirectory("standalone-candidate-goal-parent").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)

      store.saveFeatureTaskWorkflow(
        workflowRow(
          "wftr-goal-parent",
          "ftr-goal",
          "bill-feature-task",
          "plan",
          RUNTIME,
        )
          .copy(
            issueKey = "SKILL-128",
            workflowStatus = WorkflowStatus.PAUSED.wireValue,
            artifactsJson = """{"plan":{"mode":"decompose"},"decomposition_runtime":{"issue_key":"SKILL-128"}}""",
          ),
        RUNTIME,
      )
      store.saveFeatureTaskWorkflow(
        workflowRow("wftr-legacy", "ftr-legacy", "bill-feature-task", "plan", RUNTIME)
          .copy(issueKey = "SKILL-128", workflowStatus = WorkflowStatus.PAUSED.wireValue),
        RUNTIME,
      )

      val candidates = store.findStandaloneFeatureTaskCandidates("SKILL-128", "repo")

      assertEquals(listOf("wftr-legacy"), candidates.map { it.workflow.workflowId })
    }
  }

  @Test
  fun `feature task execution identity can be read exactly at adoption boundary`() {
    val dbPath = Files.createTempDirectory("goal-child-identity-read").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val workflow = goalChildWorkflow("wftr-child", "wftr-parent")
      val identity = goalChildIdentity(workflow)

      store.saveFeatureTaskWorkflow(workflow, RUNTIME)
      store.saveFeatureTaskExecutionIdentity(identity)

      assertEquals(identity, store.getFeatureTaskExecutionIdentity(identity.workflowId))
      assertEquals(null, store.getFeatureTaskExecutionIdentity("wftr-missing"))
    }
  }

  @Test
  fun `hard reset deletion removes only goal children owned by the parent workflow`() {
    val dbPath = Files.createTempDirectory("goal-child-reset").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val target = goalChildWorkflow("wftr-target", "wftr-parent")
      val siblingGoal = goalChildWorkflow("wftr-other-goal", "wftr-other-parent")
      val standalone = goalChildWorkflow("wftr-standalone", "wftr-parent")
      listOf(target, siblingGoal, standalone).forEach { row -> store.saveFeatureTaskWorkflow(row, RUNTIME) }
      listOf(target, siblingGoal).forEach { row -> store.saveFeatureTaskExecutionIdentity(goalChildIdentity(row)) }
      store.saveFeatureTaskExecutionIdentity(
        goalChildIdentity(standalone).copy(routeScope = FeatureTaskRouteScope.STANDALONE),
      )

      assertEquals(1, store.deleteGoalChildWorkflowsByParent("wftr-parent"))

      assertEquals(null, store.getFeatureTaskWorkflowAsMode(target.workflowId, RUNTIME))
      assertNotNull(store.getFeatureTaskWorkflowAsMode(siblingGoal.workflowId, RUNTIME))
      assertNotNull(store.getFeatureTaskWorkflowAsMode(standalone.workflowId, RUNTIME))
    }
  }

  @Test
  fun `scoped child deletion binds workflow status wire values`() {
    val dbPath = Files.createTempDirectory("goal-child-scoped-delete").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val completed =
        goalChildWorkflow("wftr-completed", "wftr-parent")
          .copy(workflowStatus = WorkflowStatus.COMPLETED.wireValue)
      val pending =
        goalChildWorkflow("wftr-pending", "wftr-parent")
          .copy(workflowStatus = WorkflowStatus.PENDING.wireValue)
      listOf(completed, pending).forEach { workflow ->
        store.saveFeatureTaskWorkflow(workflow, RUNTIME)
        store.saveFeatureTaskExecutionIdentity(goalChildIdentity(workflow))
      }

      assertEquals(
        1,
        store.deleteGoalChildWorkflow(
          parentWorkflowId = "wftr-parent",
          subtaskId = 1,
          workflowId = completed.workflowId,
          scope = GoalChildWorkflowDeletionScope.TERMINAL_ONLY,
        ),
      )
      assertEquals(null, store.getFeatureTaskWorkflowAsMode(completed.workflowId, RUNTIME))
      assertNotNull(store.getFeatureTaskWorkflowAsMode(pending.workflowId, RUNTIME))
    }
  }

  @Test
  fun `expired-lease non-terminal row is a crash-reconciliation candidate but live-lease and terminal are not`() {
    val dbPath = Files.createTempDirectory("crash-reconcile-candidates").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)

      seedRunningRowWithLease(store, "wftr-expired", "owner-token-expired1", expiresAt = "2026-07-14T10:05:00Z")

      seedRunningRowWithLease(store, "wftr-live", "owner-token-live00001", expiresAt = "2999-01-01T00:00:00Z")

      store.saveFeatureTaskWorkflow(
        workflowRow("wftr-done", "ftr-done", "bill-feature-task", "implement", RUNTIME)
          .copy(workflowStatus = WorkflowStatus.COMPLETED.wireValue),
        RUNTIME,
      )

      val candidates = store.findFeatureTaskRuntimeCrashReconciliationCandidates("2026-07-14T10:06:00Z")

      assertEquals(listOf("wftr-expired"), candidates.map { it.ownership.workflowId })
      assertEquals("implement", candidates.single().currentStepId)
    }
  }

  @Test
  fun `crash reconcile transitions the row to pending, releases the lease, and keeps phase and artifacts`() {
    val dbPath = Files.createTempDirectory("crash-reconcile-write").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val row =
        workflowRow(
          "wftr-crash",
          "ftr-crash",
          "bill-feature-task",
          "implement",
          RUNTIME,
        ).copy(
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          artifactsJson = """{"phase_records":{"preplan":"done"}}""",
        )
      store.saveFeatureTaskWorkflow(row, RUNTIME)
      val updatedAt = assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).updatedAt
      val ownership = workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-crash0001")
      assertTrue(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))

      val reconciled =
        store.reconcileFeatureTaskRuntimeCrashedWorker(
          workflowId = row.workflowId,
          ownerToken = ownership.ownerToken,
          generation = ownership.generation,
          interruptionReason = "lease_expired: worker lease expired and process confirmed dead",
          nowInstant = "2026-07-14T10:06:00Z",
        )

      assertTrue(reconciled)
      val after = assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME))
      assertEquals("pending", after.workflowStatus)
      assertEquals("implement", after.currentStepId)
      assertEquals(row.artifactsJson, after.artifactsJson)
      assertEquals(null, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
      assertEquals(
        "lease_expired: worker lease expired and process confirmed dead",
        interruptionReasonOf(connection, row.workflowId),
      )

      assertTrue(store.findFeatureTaskRuntimeCrashReconciliationCandidates("2026-07-14T10:06:00Z").isEmpty())
    }
  }

  @Test
  fun `crash reconcile is rejected when owner_token or generation fencing no longer matches`() {
    val dbPath = Files.createTempDirectory("crash-reconcile-fencing").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val row =
        workflowRow(
          "wftr-fence",
          "ftr-fence",
          "bill-feature-task",
          "implement",
          RUNTIME,
        ).copy(workflowStatus = WorkflowStatus.RUNNING.wireValue)
      store.saveFeatureTaskWorkflow(row, RUNTIME)
      val updatedAt = assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).updatedAt
      val ownership = workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-fence0001")
      assertTrue(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))

      val wrongToken =
        store.reconcileFeatureTaskRuntimeCrashedWorker(
          row.workflowId,
          "owner-token-stale0001",
          1,
          "reason",
          "2026-07-14T10:06:00Z",
        )
      val wrongGeneration =
        store.reconcileFeatureTaskRuntimeCrashedWorker(
          row.workflowId,
          ownership.ownerToken,
          99,
          "reason",
          "2026-07-14T10:06:00Z",
        )

      assertEquals(false, wrongToken)
      assertEquals(false, wrongGeneration)
      assertEquals("running", assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).workflowStatus)
      assertEquals(ownership, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
    }
  }

  @Test
  fun `runtime worker ownership is acquired fenced transferred heartbeated and released`() {
    val dbPath = Files.createTempDirectory("runtime-worker-lease").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val row =
        workflowRow(
          workflowId = "wftr-worker",
          sessionId = "ftr-worker",
          workflowName = "bill-feature-task",
          currentStepId = "implement",
          mode = RUNTIME,
        ).copy(workflowStatus = WorkflowStatus.RUNNING.wireValue)
      store.saveFeatureTaskWorkflow(row, RUNTIME)
      val updatedAt = assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).updatedAt
      val initial = workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-0001")

      assertTrue(store.acquireFeatureTaskRuntimeWorker(initial, updatedAt))
      assertEquals(initial, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
      assertTrue(store.reserveFeatureTaskRuntimeWorkerTakeover(row.workflowId, initial.ownerToken, 1))
      val replacement = workerOwnership(row.workflowId, generation = 2, ownerToken = "owner-token-0002")
      assertTrue(store.transferFeatureTaskRuntimeWorker(replacement, initial.ownerToken, 1))
      assertEquals(replacement, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
      assertTrue(store.heartbeatFeatureTaskRuntimeWorker(replacement.copy(heartbeatAt = "2026-07-14T10:01:00Z")))
      assertTrue(store.releaseFeatureTaskRuntimeWorker(row.workflowId, replacement.ownerToken, 2))
      assertEquals(null, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
    }
  }

  @Test
  fun `expiry-guarded worker release keeps a lease that has not reached its expiry instant`() {
    val dbPath = Files.createTempDirectory("runtime-worker-expiry").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val row =
        workflowRow(
          workflowId = "wftr-expiry",
          sessionId = "ftr-expiry",
          workflowName = "bill-feature-task",
          currentStepId = "implement",
          mode = RUNTIME,
        ).copy(workflowStatus = WorkflowStatus.RUNNING.wireValue)
      store.saveFeatureTaskWorkflow(row, RUNTIME)
      val updatedAt = assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).updatedAt
      val ownership = workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-0005")
      assertTrue(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))

      assertEquals(
        false,
        store.releaseFeatureTaskRuntimeWorkerIfExpired(
          row.workflowId,
          ownership.ownerToken,
          ownership.generation,
          "2026-07-14T10:04:59Z",
        ),
      )
      assertEquals(ownership, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))

      assertTrue(
        store.releaseFeatureTaskRuntimeWorkerIfExpired(
          row.workflowId,
          ownership.ownerToken,
          ownership.generation,
          ownership.expiresAt,
        ),
      )
      assertEquals(null, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
    }
  }

  @Test
  fun `expiry-guarded worker release keeps a lease a heartbeat extended past the original expiry`() {
    val dbPath = Files.createTempDirectory("runtime-worker-expiry-heartbeat").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val row =
        workflowRow(
          workflowId = "wftr-expiry-heartbeat",
          sessionId = "ftr-expiry-heartbeat",
          workflowName = "bill-feature-task",
          currentStepId = "implement",
          mode = RUNTIME,
        ).copy(workflowStatus = WorkflowStatus.RUNNING.wireValue)
      store.saveFeatureTaskWorkflow(row, RUNTIME)
      val updatedAt = assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).updatedAt
      val ownership = workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-0006")
      assertTrue(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))
      val extended = ownership.copy(heartbeatAt = "2026-07-14T10:04:00Z", expiresAt = "2026-07-14T10:09:00Z")
      assertTrue(store.heartbeatFeatureTaskRuntimeWorker(extended))

      assertEquals(
        false,
        store.releaseFeatureTaskRuntimeWorkerIfExpired(
          row.workflowId,
          ownership.ownerToken,
          ownership.generation,
          ownership.expiresAt,
        ),
      )
      assertEquals(extended, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
    }
  }

  @Test
  fun `runtime worker takeover reservation is single caller CAS`() {
    val dbPath = Files.createTempDirectory("runtime-worker-contention").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val row =
        workflowRow(
          workflowId = "wftr-contention",
          sessionId = "ftr-contention",
          workflowName = "bill-feature-task",
          currentStepId = "implement",
          mode = RUNTIME,
        ).copy(workflowStatus = WorkflowStatus.PAUSED.wireValue)
      store.saveFeatureTaskWorkflow(row, RUNTIME)
      val updatedAt = assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).updatedAt
      val ownership = workerOwnership(row.workflowId, generation = 4, ownerToken = "owner-token-0004")
      assertTrue(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))

      assertTrue(store.reserveFeatureTaskRuntimeWorkerTakeover(row.workflowId, ownership.ownerToken, 4))
      assertEquals(false, store.reserveFeatureTaskRuntimeWorkerTakeover(row.workflowId, ownership.ownerToken, 4))
    }
  }

  @Test
  fun `runtime worker ownership rejects malformed and inverted lease timestamps at read seam`() {
    val dbPath = Files.createTempDirectory("runtime-worker-invalid-lease").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val row =
        workflowRow(
          workflowId = "wftr-invalid-lease",
          sessionId = "ftr-invalid-lease",
          workflowName = "bill-feature-task",
          currentStepId = "implement",
          mode = RUNTIME,
        ).copy(workflowStatus = WorkflowStatus.PAUSED.wireValue)
      store.saveFeatureTaskWorkflow(row, RUNTIME)
      val updatedAt = assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).updatedAt
      assertTrue(
        store.acquireFeatureTaskRuntimeWorker(
          workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-0001"),
          updatedAt,
        ),
      )

      connection.prepareStatement(
        "UPDATE feature_task_runtime_worker_leases SET heartbeat_at = ? WHERE workflow_id = ?",
      ).use {
        it.setString(1, "not-an-instant")
        it.setString(2, row.workflowId)
        it.executeUpdate()
      }
      assertFailsWith<InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError> {
        store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId)
      }

      connection.prepareStatement(
        "UPDATE feature_task_runtime_worker_leases SET heartbeat_at = ?, expires_at = ? WHERE workflow_id = ?",
      ).use {
        it.setString(1, "2026-07-14T10:05:00Z")
        it.setString(2, "2026-07-14T10:05:00Z")
        it.setString(3, row.workflowId)
        it.executeUpdate()
      }
      assertFailsWith<InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError> {
        store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId)
      }
    }
  }

  @Test
  fun `feature task runtime table contract version default matches schema contract version const`() {
    assertEquals(
      WORKFLOW_STATE_CONTRACT_VERSION,
      FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION,
      "FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION must equal WORKFLOW_STATE_CONTRACT_VERSION " +
        "($WORKFLOW_STATE_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `feature verify workflow rows preserve explicit contract version and json fields`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflows").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)

      verifyWorkflowStore(connection).saveWorkflow(
        WorkflowStateRecord(
          workflowId = "wfv-001",
          sessionId = "fvr-001",
          workflowName = "bill-feature-verify",
          contractVersion = "0.1",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "code_review",
          stepsJson = """[{"step_id":"code_review","status":"running","attempt_count":1}]""",
          artifactsJson = """{"review_result":{"verdict":"approve"}}""",
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
        ),
      )

      val saved = assertNotNull(verifyWorkflowStore(connection).getWorkflow("wfv-001"))
      assertEquals("bill-feature-verify", saved.workflowName)
      assertEquals("0.1", saved.contractVersion)
      assertEquals("code_review", saved.currentStepId)
      assertEquals("""{"review_result":{"verdict":"approve"}}""", saved.artifactsJson)
    }
  }

  @Test
  fun `terminal workflow update records finished timestamp when caller leaves it empty`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-terminal").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val initialRow =
        workflowRow(
          workflowId = "wftr-terminal",
          sessionId = "ftr-terminal",
          workflowName = "bill-feature-task",
          currentStepId = "preplan",
          mode = RUNTIME,
        )

      store.saveFeatureTaskWorkflow(initialRow, RUNTIME)
      store.saveFeatureTaskWorkflow(
        initialRow.copy(
          workflowStatus = WorkflowStatus.ABANDONED.wireValue,
          currentStepId = "pr",
          finishedAt = "",
        ),
        RUNTIME,
      )

      val saved = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-terminal", RUNTIME))
      assertEquals("abandoned", saved.workflowStatus)
      assertNotNull(saved.finishedAt)
    }
  }

  @Test
  fun `paused parent workflow round-trips under the same id and is not stamped finished`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-paused").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val initialRow =
        workflowRow(
          workflowId = "wftr-paused-parent",
          sessionId = "ftr-paused-parent",
          workflowName = "bill-feature-task",
          currentStepId = "plan",
          mode = RUNTIME,
        ).copy(artifactsJson = """{"plan":{"mode":"decompose"}}""")

      store.saveFeatureTaskWorkflow(initialRow, RUNTIME)
      store.saveFeatureTaskWorkflow(
        initialRow.copy(workflowStatus = WorkflowStatus.PAUSED.wireValue, finishedAt = null),
        RUNTIME,
      )

      val saved = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-paused-parent", RUNTIME))
      assertEquals("wftr-paused-parent", saved.workflowId)
      assertEquals("paused", saved.workflowStatus)
      assertEquals("plan", saved.currentStepId)
      assertEquals("""{"plan":{"mode":"decompose"}}""", saved.artifactsJson)
      assertEquals(null, saved.finishedAt)
    }
  }
}

class WorkflowStateStoreLifecycleTest {
  @Test
  fun `workflow state entry starts at supplied start time and changes only on a status transition`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-state-entry").resolve("metrics.db")
    val startedAt = "2999-05-01T12:00:00.123456789Z"

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val initial =
        workflowRow(
          workflowId = "wftr-state-entry-main",
          sessionId = "ftr-state-entry-main",
          workflowName = "bill-feature-task",
          currentStepId = "preplan",
          mode = RUNTIME,
        ).copy(startedAt = startedAt)

      store.saveFeatureTaskWorkflow(initial, RUNTIME)
      val inserted = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-state-entry-main", RUNTIME))
      assertEquals(startedAt, inserted.startedAt)
      assertEquals(startedAt, inserted.stateEnteredAt)
      assertEquals(false, inserted.stateEnteredAtEstimated)

      store.saveFeatureTaskWorkflow(initial.copy(currentStepId = "plan", artifactsJson = "{\"plan\":{}}"), RUNTIME)
      val sameStatus = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-state-entry-main", RUNTIME))
      assertEquals(startedAt, sameStatus.stateEnteredAt)
      assertEquals(false, sameStatus.stateEnteredAtEstimated)

      store.saveFeatureTaskWorkflow(
        sameStatus.copy(workflowStatus = WorkflowStatus.BLOCKED.wireValue, currentStepId = "plan"),
        RUNTIME,
      )
      val transitioned = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-state-entry-main", RUNTIME))
      assertEquals("blocked", transitioned.workflowStatus)
      assertTrue(Instant.parse(transitioned.stateEnteredAt).isAfter(Instant.parse(startedAt)))
      assertEquals(false, transitioned.stateEnteredAtEstimated)

      assertRuntimeAndVerifyStateTransitions(connection, initial, startedAt)
    }
  }

  @Test
  fun `workflow inserts without a supplied start time use one effective timestamp`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-insert-state-entry").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      store.saveFeatureTaskWorkflow(
        workflowRow(
          workflowId = "wftr-insert",
          sessionId = "ftr-insert",
          workflowName = "bill-feature-task",
          currentStepId = "preplan",
          mode = RUNTIME,
        ),
        RUNTIME,
      )
      verifyWorkflowStore(connection).saveWorkflow(
        workflowRow("wfv-insert", "fvr-insert", "bill-feature-verify", "collect_inputs"),
      )

      listOf(
        assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-insert", RUNTIME)),
        assertNotNull(verifyWorkflowStore(connection).getWorkflow("wfv-insert")),
      ).forEach { inserted ->
        assertEquals(inserted.startedAt, inserted.stateEnteredAt)
        assertEquals(false, inserted.stateEnteredAtEstimated)
        assertTrue(!inserted.startedAt.isNullOrBlank())
      }
    }
  }

  @Test
  fun `concurrent workflow status transitions serialize strictly increasing state entry times`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-concurrent-state").resolve("metrics.db")
    val initial =
      workflowRow(
        workflowId = "wftr-concurrent-state-entry",
        sessionId = "ftr-concurrent-state-entry",
        workflowName = "bill-feature-task",
        currentStepId = "preplan",
        mode = RUNTIME,
      ).copy(startedAt = "2999-05-01T12:00:00.123456789Z")

    prepareConcurrentWorkflowTransitions(dbPath, initial)

    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val transitions =
        listOf("blocked", "failed").map { status ->
          executor.submit {
            ready.countDown()
            check(start.await(5, TimeUnit.SECONDS)) { "Concurrent transition start timed out." }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
              connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
              WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
                .saveFeatureTaskWorkflow(
                  initial.copy(workflowStatus = status, currentStepId = "plan"),
                  RUNTIME,
                )
            }
          }
        }

      assertTrue(ready.await(5, TimeUnit.SECONDS))
      start.countDown()
      transitions.forEach { it.get(5, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val entries =
        connection.createStatement().use { statement ->
          statement.executeQuery("SELECT state_entered_at FROM workflow_transition_log ORDER BY rowid").use {
              resultSet ->
            buildList {
              while (resultSet.next()) {
                add(resultSet.getString("state_entered_at"))
              }
            }
          }
        }

      assertEquals(2, entries.size)
      assertTrue(Instant.parse(entries[1]).isAfter(Instant.parse(entries[0])))
    }
  }

  @Test
  fun `workflow lists and latest use updated timestamp then rowid ordering for both families`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-list").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)

      listOf("wftr-001", "wftr-002", "wftr-003").forEachIndexed { index, workflowId ->
        store.saveFeatureTaskWorkflow(
          workflowRow(
            workflowId = workflowId,
            sessionId = "ftr-00$index",
            workflowName = "bill-feature-task",
            currentStepId = "preplan",
            mode = RUNTIME,
          ),
          RUNTIME,
        )
      }
      listOf("wfv-001", "wfv-002").forEachIndexed { index, workflowId ->
        verifyWorkflowStore(connection).saveWorkflow(
          workflowRow(
            workflowId = workflowId,
            sessionId = "fvr-00$index",
            workflowName = "bill-feature-verify",
            currentStepId = "gather_diff",
          ),
        )
      }

      assertEquals(listOf("wftr-003", "wftr-002"), store.listFeatureTaskWorkflows(RUNTIME, 2).map { it.workflowId })
      assertEquals("wftr-003", store.latestFeatureTaskWorkflow(RUNTIME)?.workflowId)
      assertEquals(listOf("wfv-002", "wfv-001"), store.list(WorkflowFamily.VERIFY, 10).map { it.workflowId })
      assertEquals("wfv-002", store.latest(WorkflowFamily.VERIFY)?.workflowId)
    }
  }

  @Test
  fun `snapshot lookups chunk id sets larger than the SQLite bind limit`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-batch").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val workflowIds =
        (0 until 950).map { index ->
          val workflowId = "wftr-batch-$index"
          store.saveFeatureTaskWorkflow(
            workflowRow(workflowId, "ftr-batch-$index", "bill-feature-task", "plan", RUNTIME),
            RUNTIME,
          )
          workflowId
        }

      val snapshots = store.getAll(WorkflowFamily.TASK_RUNTIME, workflowIds.toSet())

      assertEquals(workflowIds.toSet(), snapshots.keys)
    }
  }

  @Test
  fun `feature task runtime workflow rows round trip with per-phase records and appended ledger`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-task-runtime").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val artifactsJson = taskRuntimeArtifactsJson

      val initialRow =
        WorkflowStateRecord(
          workflowId = "wftr-001",
          sessionId = "ftr-001",
          workflowName = "bill-feature-task",
          mode = RUNTIME,
          contractVersion = "",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "plan",
          stepsJson = """[{"step_id":"plan","status":"completed"}]""",
          artifactsJson = artifactsJson,
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
        )

      store.saveFeatureTaskWorkflow(initialRow, RUNTIME)

      val saved = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-001", RUNTIME))
      assertEquals(FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION, saved.contractVersion)
      assertEquals("bill-feature-task", saved.workflowName)
      assertEquals(RUNTIME, saved.mode)
      assertEquals("bill-feature", saved.implementationSkill)
      assertEquals("plan", saved.currentStepId)
      assertEquals(artifactsJson, saved.artifactsJson)

      assertFailsWith<InvalidWorkflowStateSchemaError> {
        store.getFeatureTaskWorkflowAsMode("wftr-001", PROSE)
      }
      assertEquals(null, store.get(WorkflowFamily.VERIFY, "wftr-001"))
      assertEquals(RUNTIME, store.getFeatureTaskWorkflow("wftr-001")?.mode)
    }
  }

  @Test
  fun `audit repair artifact round trips through sqlite without rewriting its contract identity`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-audit-repair").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val row =
        workflowRow(
          workflowId = "wftr-audit-repair",
          sessionId = "ftr-audit-repair",
          workflowName = "bill-feature-task",
          currentStepId = "implement",
          mode = RUNTIME,
        ).copy(artifactsJson = auditRepairArtifactsJson())

      store.saveFeatureTaskWorkflow(row, RUNTIME)

      assertEquals(
        auditRepairArtifactsJson(),
        assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).artifactsJson,
      )

      store.saveFeatureTaskWorkflow(row.copy(artifactsJson = auditRepairArtifactsJson("9.9")), RUNTIME)
      assertEquals(
        auditRepairArtifactsJson("9.9"),
        assertNotNull(store.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME)).artifactsJson,
        "SQLite must preserve incompatible identity for production mapping to reject at runtime use.",
      )
    }
  }

  @Test
  fun `feature task runtime upsert preserves the original started_at across a second save`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-task-runtime-started").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      val initialRow =
        workflowRow(
          workflowId = "wftr-started",
          sessionId = "ftr-started",
          workflowName = "bill-feature-task",
          currentStepId = "plan",
          mode = RUNTIME,
        )

      store.saveFeatureTaskWorkflow(initialRow, RUNTIME)
      val firstStartedAt = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-started", RUNTIME)).startedAt
      assertNotNull(firstStartedAt)

      store.saveFeatureTaskWorkflow(
        initialRow.copy(currentStepId = "implement", startedAt = "2099-01-01 00:00:00"),
        RUNTIME,
      )

      val resaved = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-started", RUNTIME))
      assertEquals(firstStartedAt, resaved.startedAt)
      assertEquals("implement", resaved.currentStepId)
    }
  }

  @Test
  fun `feature task runtime workflow lists and latest use updated timestamp then rowid ordering`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-task-runtime-list").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)

      listOf("wftr-001", "wftr-002", "wftr-003").forEachIndexed { index, workflowId ->
        store.saveFeatureTaskWorkflow(
          workflowRow(
            workflowId = workflowId,
            sessionId = "ftr-00$index",
            workflowName = "bill-feature-task",
            currentStepId = "plan",
            mode = RUNTIME,
          ),
          RUNTIME,
        )
      }

      assertEquals(listOf("wftr-003", "wftr-002"), store.listFeatureTaskWorkflows(RUNTIME, 2).map { it.workflowId })
      assertEquals("wftr-003", store.latestFeatureTaskWorkflow(RUNTIME)?.workflowId)
      assertTrue(store.listFeatureTaskWorkflows(PROSE, 10).isEmpty())
      assertTrue(store.list(WorkflowFamily.VERIFY, 10).isEmpty())
    }
  }

  @Test
  fun `workflow session summaries preserve started payload shape`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-sessions").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      insertFeatureVerifySession(connection)

      val verifySummary = assertNotNull(verifyWorkflowStore(connection).getSessionSummary("fvr-session"))

      assertEquals(4, verifySummary.acceptanceCriteriaCount)
      assertEquals(true, verifySummary.rolloutRelevant)
      assertEquals("Verify workflow runtime", verifySummary.specSummary)
    }
  }

  @Test
  fun `quarantined legacy prose rows stay readable but are never silently routed as runtime`() {
    val dbPath = Files.createTempDirectory("legacy-prose-quarantine").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO feature_task_workflows (
          workflow_id, session_id, workflow_name, mode, implementation_skill, contract_version,
          workflow_status, current_step_id, steps_json, artifacts_json, issue_key,
          started_at, updated_at, state_entered_at, state_entered_at_estimated, finished_at
        ) VALUES (?, ?, 'bill-feature-task', 'prose', 'bill-feature-task-prose', ?, 'running', 'plan',
                  '[]', '{}', 'SKILL-901', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, NULL)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "wfl-legacy-prose-001")
        statement.setString(2, "fis-legacy-prose-001")
        statement.setString(3, FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION)
        statement.executeUpdate()
      }

      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)

      val prose = assertNotNull(store.getFeatureTaskWorkflowAsMode("wfl-legacy-prose-001", PROSE))
      assertEquals(PROSE, prose.mode)
      assertEquals("bill-feature-task-prose", prose.implementationSkill)
      assertEquals(FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION, prose.contractVersion)

      val generic = assertNotNull(store.getFeatureTaskWorkflow("wfl-legacy-prose-001"))
      assertEquals(PROSE, generic.mode)
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        store.getFeatureTaskWorkflowAsMode("wfl-legacy-prose-001", RUNTIME)
      }

      assertFailsWith<ProseFeatureTaskWorkflowWriteRefusedError> {
        store.saveFeatureTaskWorkflow(prose, PROSE)
      }
    }
  }

  @Test
  fun `terminalizeLegacyProseFeatureTaskWorkflow preserves mode and records status artifacts`() {
    val dbPath = Files.createTempDirectory("legacy-prose-terminalize").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO feature_task_workflows (
          workflow_id, session_id, workflow_name, mode, implementation_skill, contract_version,
          workflow_status, current_step_id, steps_json, artifacts_json, issue_key,
          started_at, updated_at, state_entered_at, state_entered_at_estimated, finished_at
        ) VALUES (?, ?, 'bill-feature-task', 'prose', 'bill-feature-task-prose', ?, 'paused', 'assess',
                  '[{"step_id":"assess","status":"completed"}]', '{"history_note":"retain-me"}', 'SKILL-179',
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, NULL)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "wfl-legacy-prose-term-001")
        statement.setString(2, "fis-legacy-prose-term-001")
        statement.setString(3, FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION)
        statement.executeUpdate()
      }

      val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
      store.terminalizeLegacyProseFeatureTaskWorkflow(
        requireNotNull(store.getFeatureTaskWorkflow("wfl-legacy-prose-term-001")).copy(
          workflowStatus = WorkflowStatus.ABANDONED.wireValue,
          artifactsJson =
            """{"history_note":"retain-me",""" +
              """"operator_abandonment":{"reason":"retire","abandoned_at":"2026-08-09T00:00:00Z"}}""",
          finishedAt = "2026-08-09T00:00:00Z",
        ),
      )

      val saved = assertNotNull(store.getFeatureTaskWorkflow("wfl-legacy-prose-term-001"))
      assertEquals(PROSE, saved.mode)
      assertEquals("abandoned", saved.workflowStatus)
      assertContains(saved.artifactsJson, "retain-me")
      assertContains(saved.artifactsJson, "operator_abandonment")
      assertFailsWith<ProseFeatureTaskWorkflowWriteRefusedError> {
        store.saveFeatureTaskWorkflow(saved, PROSE)
      }
    }
  }
}
