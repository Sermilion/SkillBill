package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.validation.coordinator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeWorkerCoordinatorTest {
  private val execution = ExecutionPlanAdmissionFixture()

  @Test
  fun `unowned acquire still claims after a concurrent updated_at bump`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.saveFeatureTaskWorkflow(unownedRuntimeRow(updatedAt = "2026-08-15T20:57:11Z"), RUNTIME)
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        BumpUpdatedAtAfterReadDatabase(repository),
        FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
        testHarnessClock,
        execution.admission,
      )

    coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) {
      val owned = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(1, owned.generation)
      assertEquals("implement", owned.phaseId)
    }

    assertNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
  }

  @Test
  fun `orphaned worker lease is atomically reclaimed with a new generation`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        RuntimeFakeDatabaseSessionFactory(repository),
        FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
        testHarnessClock,
        execution.admission,
      )

    coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) {
      val replacement = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(2, replacement.generation)
      assertNotEquals("old-owner-token-0001", replacement.ownerToken)
    }

    assertNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
  }

  @Test
  fun `exact live worker is stopped before ownership transfer`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val supervisor = FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        RuntimeFakeDatabaseSessionFactory(repository),
        supervisor,
        testHarnessClock,
        execution.admission,
      )

    coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) { Unit }

    assertTrue(supervisor.gracefulTerminationRequested)
    assertEquals(false, supervisor.forceTerminationRequested)
  }

  @Test
  fun `PID reuse ownership mismatch rejects takeover without terminating`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val supervisor =
      FakeWorkerSupervisor(
        FeatureTaskRuntimeProcessInspection.OwnershipMismatch("Worker PID was reused by a different process."),
      )
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        RuntimeFakeDatabaseSessionFactory(repository),
        supervisor,
        testHarnessClock,
        execution.admission,
      )

    val failure =
      assertFailsWith<IllegalStateException> {
        coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) { Unit }
      }

    assertTrue(failure.message.orEmpty().contains("PID was reused"))
    assertEquals(false, supervisor.gracefulTerminationRequested)
  }

  @Test
  fun `expired ownership mismatch is reclaimed without terminating an unrelated process`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership(expiresAt = "2000-01-01T00:00:30Z"))
    val supervisor =
      FakeWorkerSupervisor(
        FeatureTaskRuntimeProcessInspection.OwnershipMismatch("Worker ownership belongs to a different host."),
      )
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        RuntimeFakeDatabaseSessionFactory(repository),
        supervisor,
        testHarnessClock,
        execution.admission,
      )

    coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) {
      val replacement = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(2, replacement.generation)
      assertNotEquals("old-owner-token-0001", replacement.ownerToken)
    }

    assertEquals(false, supervisor.gracefulTerminationRequested)
    assertEquals(false, supervisor.forceTerminationRequested)
  }

  @Test
  fun `expired unsupported inspection is reclaimed without terminating an unrelated process`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership(expiresAt = "2000-01-01T00:00:30Z"))
    val supervisor =
      FakeWorkerSupervisor(
        FeatureTaskRuntimeProcessInspection.Unsupported("Process inspection is unavailable on this host."),
      )
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        RuntimeFakeDatabaseSessionFactory(repository),
        supervisor,
        testHarnessClock,
        execution.admission,
      )

    coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) {
      val replacement = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(2, replacement.generation)
      assertNotEquals("old-owner-token-0001", replacement.ownerToken)
    }

    assertEquals(false, supervisor.gracefulTerminationRequested)
    assertEquals(false, supervisor.forceTerminationRequested)
  }

  @Test
  fun `concurrent recovery contention permits only one lease transfer`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val stale = ownership()
    repository.seedWorkerOwnership(stale)
    assertTrue(repository.reserveFeatureTaskRuntimeWorkerTakeover(WORKFLOW_ID, stale.ownerToken, stale.generation))
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        RuntimeFakeDatabaseSessionFactory(repository),
        FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
        testHarnessClock,
        execution.admission,
      )

    val failure =
      assertFailsWith<IllegalStateException> {
        coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) { Unit }
      }

    assertTrue(failure.message.orEmpty().contains("Concurrent continuation"))
  }

  @Test
  fun `a heartbeat tick renews the lease while this process still owns it`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val supervisor = FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        RuntimeFakeDatabaseSessionFactory(repository),
        supervisor,
        testHarnessClock,
        execution.admission,
      )

    coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) {
      val owned = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(FeatureTaskRuntimeHeartbeatTick.Renewed, supervisor.runHeartbeatTick())
      val renewed = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(owned.ownerToken, renewed.ownerToken)
      assertTrue(Instant.parse(renewed.expiresAt) >= Instant.parse(owned.expiresAt))
    }

    assertEquals(WORKFLOW_ID, supervisor.capturedPlan?.label)
    assertEquals(LEASE_SECONDS, supervisor.capturedPlan?.leaseSeconds)
  }

  @Test
  fun `a heartbeat tick that lost fencing fails the phase instead of reporting success`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val supervisor = FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)
    execution.seed(repository, WORKFLOW_ID)
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        RuntimeFakeDatabaseSessionFactory(repository),
        supervisor,
        testHarnessClock,
        execution.admission,
      )

    val failure =
      assertFailsWith<IllegalStateException> {
        coordinator.runOwned(WORKFLOW_ID, execution.inputs, execution.identity(WORKFLOW_ID)) {
          repository.seedWorkerOwnership(ownership(ownerToken = "usurper-token-0002", generation = 9))
          assertTrue(supervisor.runHeartbeatTick() is FeatureTaskRuntimeHeartbeatTick.FencingLost)
        }
      }

    assertTrue(failure.message.orEmpty().contains("lost lease fencing mid-phase"))
  }
}

private const val LEASE_SECONDS = 30L

private class FakeWorkerSupervisor(
  initialInspection: FeatureTaskRuntimeProcessInspection,
) : FeatureTaskRuntimeWorkerSupervisor {
  private var inspection = initialInspection
  private var tick: (() -> FeatureTaskRuntimeHeartbeatTick)? = null
  private var fencingLostReason: String? = null
  var gracefulTerminationRequested = false
  var forceTerminationRequested = false
  var capturedPlan: FeatureTaskRuntimeHeartbeatPlan? = null
    private set

  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 200, "birth-200")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) = Unit

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    gracefulTerminationRequested = true
    inspection = FeatureTaskRuntimeProcessInspection.NotRunning
    return true
  }

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    forceTerminationRequested = true
    inspection = FeatureTaskRuntimeProcessInspection.NotRunning
    return true
  }

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat {
    capturedPlan = plan
    tick = heartbeat
    return object : FeatureTaskRuntimeHeartbeat {
      override fun stop() = Unit

      override fun fencingLostReason(): String? = fencingLostReason
    }
  }

  fun runHeartbeatTick(): FeatureTaskRuntimeHeartbeatTick {
    val outcome = requireNotNull(tick) { "startHeartbeat was never called." }.invoke()
    if (outcome is FeatureTaskRuntimeHeartbeatTick.FencingLost) fencingLostReason = outcome.reason
    return outcome
  }

  override fun pause(durationMillis: Long) = Unit
}

private class BumpUpdatedAtAfterReadDatabase(
  private val workflows: InMemoryRuntimeWorkflowRepository,
) : DatabaseSessionFactory {
  private val inner = RuntimeFakeDatabaseSessionFactory(workflows)

  override fun resolveDbPath() = inner.resolveDbPath()

  override fun databaseExists() = inner.databaseExists()

  override fun <T> read(block: (UnitOfWork) -> T): T {
    val result = inner.read(block)
    workflows.bumpUpdatedAt(WORKFLOW_ID)
    return result
  }

  override fun <T> transaction(block: (UnitOfWork) -> T): T = inner.transaction(block)

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = inner.selfManagedWrite(block)
}

private fun unownedRuntimeRow(updatedAt: String) =
  WorkflowStateRecord(
    workflowId = WORKFLOW_ID,
    sessionId = "ftr-unowned",
    workflowName = "bill-feature-task",
    contractVersion = "0.1",
    workflowStatus = WorkflowStatus.PENDING.wireValue,
    currentStepId = "implement",
    stepsJson = "[]",
    artifactsJson = "{}",
    startedAt = "2026-08-15T20:57:11Z",
    updatedAt = updatedAt,
    finishedAt = null,
    mode = RUNTIME,
  )

internal fun ownership(
  expiresAt: String = "2999-01-01T00:00:30Z",
  ownerToken: String = "old-owner-token-0001",
  generation: Long = 1,
) = FeatureTaskRuntimeWorkerOwnership(
  workflowId = WORKFLOW_ID,
  generation = generation,
  ownerToken = ownerToken,
  hostIdentity = "host",
  bootIdentity = "boot",
  pid = 100,
  processBirthToken = "birth-100",
  leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
  heartbeatAt = "2026-07-14T10:00:00Z",
  expiresAt = expiresAt,
  phaseId = "implement",
  phaseAttempt = 1,
)
