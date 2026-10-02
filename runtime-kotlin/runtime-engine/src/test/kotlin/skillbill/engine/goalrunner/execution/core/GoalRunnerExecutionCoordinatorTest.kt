package skillbill.engine.goalrunner.execution.core

import skillbill.engine.featuretask.validation.coordinator
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_STOP
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GoalRunnerExecutionCoordinatorTest {
  @Test
  fun `confirmed dead parent lease is reclaimed with a new generation`() {
    val store = InMemoryExecutionLeaseStore(lease(generation = 1, ownerToken = "old-owner"))
    val coordinator = testCoordinator(store, FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning))

    val result =
      coordinator.runOwned("parent-1") {
        assertEquals(2, requireNotNull(store.executionLeaseValue).generation)
        "continued"
      }

    assertEquals(GoalRunnerOwnedRun.Completed("continued"), result)
    assertNull(store.executionLeaseValue)
  }

  @Test
  fun `expired parent lease with ownership mismatch reclaims and runs`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(generation = 1, ownerToken = "old-owner", expiresAt = EXPIRED_LEASE_EXPIRES_AT),
      )
    val supervisor =
      FakeGoalSupervisor(
        FeatureTaskRuntimeProcessInspection.OwnershipMismatch("Worker PID was reused by a different process."),
      )
    val coordinator = testCoordinator(store, supervisor)

    val result =
      coordinator.runOwned("parent-1") {
        assertEquals(2, requireNotNull(store.executionLeaseValue).generation)
        "continued"
      }

    assertEquals(GoalRunnerOwnedRun.Completed("continued"), result)
    assertNull(store.executionLeaseValue)
  }

  @Test
  fun `expired parent lease with unsupported inspection reclaims and runs`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(generation = 1, ownerToken = "old-owner", expiresAt = EXPIRED_LEASE_EXPIRES_AT),
      )
    val supervisor =
      FakeGoalSupervisor(
        FeatureTaskRuntimeProcessInspection.Unsupported("Process inspection is unavailable on this host."),
      )
    val coordinator = testCoordinator(store, supervisor)

    val result =
      coordinator.runOwned("parent-1") {
        assertEquals(2, requireNotNull(store.executionLeaseValue).generation)
        "continued"
      }

    assertEquals(GoalRunnerOwnedRun.Completed("continued"), result)
    assertNull(store.executionLeaseValue)
  }

  @Test
  fun `relaunch after expired lease clears interrupted pause`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(generation = 1, ownerToken = "old-owner", expiresAt = EXPIRED_LEASE_EXPIRES_AT),
      )
    store.controlStateValue =
      GoalRunnerControlState(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
        pausedAt = "2026-08-02T09:59:00Z",
      )
    val coordinator =
      testCoordinator(
        store,
        FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
      )

    coordinator.runOwned("parent-1") { "continued" }

    assertEquals(GoalRunnerControlState(), store.controlStateValue)
    assertEquals(1, store.persistControlStateCalls)
  }

  @Test
  fun `operator stop pause is preserved after expired lease reclaim`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(generation = 1, ownerToken = "old-owner", expiresAt = EXPIRED_LEASE_EXPIRES_AT),
      )
    store.controlStateValue =
      GoalRunnerControlState(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = GOAL_PAUSE_REASON_OPERATOR_STOP,
        pausedAt = "2026-08-02T09:00:00Z",
      )
    val coordinator =
      testCoordinator(
        store,
        FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
      )

    coordinator.runOwned("parent-1") { "continued" }

    assertEquals(GOAL_PAUSE_REASON_OPERATOR_STOP, store.controlStateValue.pauseReason)
    assertEquals("2026-08-02T09:00:00Z", store.controlStateValue.pausedAt)
    assertTrue(store.controlStateValue.paused)
    assertEquals(0, store.persistControlStateCalls)
  }

  @Test
  fun `issue 342 expired lease relaunch clears interrupted pause`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(generation = 1, ownerToken = "old-owner", expiresAt = EXPIRED_LEASE_EXPIRES_AT),
      )
    store.controlStateValue =
      GoalRunnerControlState(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
        pausedAt = "2026-08-02T09:59:00Z",
      )
    val supervisor =
      FakeGoalSupervisor(
        FeatureTaskRuntimeProcessInspection.OwnershipMismatch("the existing process owner is ambiguous"),
      )
    val coordinator = testCoordinator(store, supervisor)

    val result =
      coordinator.runOwned("parent-1") {
        assertEquals(2, requireNotNull(store.executionLeaseValue).generation)
        "goal body ran"
      }

    assertEquals(GoalRunnerOwnedRun.Completed("goal body ran"), result)
    assertEquals(GoalRunnerControlState(), store.controlStateValue)
    assertNull(store.executionLeaseValue)
  }

  @Test
  fun `a long-running parent lease still blocks a second foreground goal runner`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(
          generation = 1,
          ownerToken = "live-owner",
          processBirthToken = staleBirthToken(),
        ),
      )
    val supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)
    val coordinator = testCoordinator(store, supervisor)

    val failure =
      assertIs<GoalRunnerOwnedRun.AlreadyRunning>(
        coordinator.runOwned("parent-1") { error("the second run must not enter the goal body") },
      )
    assertTrue(failure.reason.contains("another goal runner process is live"))
    assertEquals(0, supervisor.awaitExitCalls)
    assertEquals("live-owner", requireNotNull(store.executionLeaseValue).ownerToken)
  }

  @Test
  fun `a duplicate-launch live lease is waited out then reclaimed`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(
          generation = 1,
          ownerToken = "live-owner",
          processBirthToken = recentBirthToken(),
        ),
      )
    val supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)
    val coordinator = testCoordinator(store, supervisor)

    val result =
      coordinator.runOwned("parent-1") {
        assertEquals(2, requireNotNull(store.executionLeaseValue).generation)
        "continued"
      }

    assertEquals(GoalRunnerOwnedRun.Completed("continued"), result)
    assertEquals(1, supervisor.awaitExitCalls)
    assertEquals(Duration.ofSeconds(60), supervisor.lastAwaitTimeout)
    assertNull(store.executionLeaseValue)
  }

  @Test
  fun `a duplicate-launch peer that stays live after awaitExit stays blocked`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(
          generation = 1,
          ownerToken = "live-owner",
          processBirthToken = recentBirthToken(),
        ),
      )
    val supervisor =
      FakeGoalSupervisor(
        FeatureTaskRuntimeProcessInspection.ExactLive,
        markNotRunningAfterAwait = false,
      )
    val coordinator = testCoordinator(store, supervisor)

    val failure =
      assertIs<GoalRunnerOwnedRun.AlreadyRunning>(
        coordinator.runOwned("parent-1") { error("must not reclaim a still-live peer") },
      )
    assertTrue(failure.reason.contains("another goal runner process is live"))
    assertEquals(1, supervisor.awaitExitCalls)
    assertEquals(Duration.ofSeconds(60), supervisor.lastAwaitTimeout)
    assertEquals("live-owner", requireNotNull(store.executionLeaseValue).ownerToken)
  }

  @Test
  fun `this process does not wait on its own execution lease`() {
    val current = FeatureTaskRuntimeProcessIdentity("host", "boot", 200, "birth-200")
    val store =
      InMemoryExecutionLeaseStore(
        lease(
          generation = 1,
          ownerToken = "self-owner",
          pid = current.pid,
          processBirthToken = current.processBirthToken,
        ),
      )
    val supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive, current)
    val coordinator = testCoordinator(store, supervisor)

    val failure =
      assertIs<GoalRunnerOwnedRun.AlreadyRunning>(
        coordinator.runOwned("parent-1") { error("must not re-enter") },
      )
    assertTrue(failure.reason.contains("this process already owns the execution lease"))
    assertEquals(0, supervisor.awaitExitCalls)
  }

  @Test
  fun `an unparseable process birth token is not treated as a duplicate launch`() {
    val store =
      InMemoryExecutionLeaseStore(
        lease(generation = 1, ownerToken = "live-owner", processBirthToken = "birth-100"),
      )
    val supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)
    val coordinator = testCoordinator(store, supervisor)

    val failure =
      assertIs<GoalRunnerOwnedRun.AlreadyRunning>(
        coordinator.runOwned("parent-1") { error("must not attach") },
      )
    assertTrue(failure.reason.contains("another goal runner process is live"))
    assertEquals(0, supervisor.awaitExitCalls)
  }

  @Test
  fun `a heartbeat tick renews the parent lease while this runner still owns it`() {
    val store = InMemoryExecutionLeaseStore(lease(generation = 1, ownerToken = "old-owner"))
    val supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)
    val coordinator = testCoordinator(store, supervisor)

    coordinator.runOwned("parent-1") {
      assertEquals(FeatureTaskRuntimeHeartbeatTick.Renewed, supervisor.runHeartbeatTick())
      assertEquals(2, requireNotNull(store.executionLeaseValue).generation)
    }

    assertEquals("parent-1", supervisor.capturedPlan?.label)
    assertEquals(PARENT_LEASE_SECONDS, supervisor.capturedPlan?.leaseSeconds)
  }

  @Test
  fun `heartbeat startup failure preserves its failure when lease cleanup also fails`() {
    val store = InMemoryExecutionLeaseStore(null)
    val failure = IllegalStateException("start heartbeat")
    val releaseFailure = IllegalStateException("release heartbeat startup lease")
    store.releaseFailure = releaseFailure
    val supervisor = FakeGoalSupervisor(NOT_RUNNING, heartbeatStartFailure = failure)
    val coordinator = testCoordinator(store, supervisor)
    var bodyRan = false

    val thrown =
      assertFailsWith<IllegalStateException> {
        coordinator.runOwned("parent-1") {
          bodyRan = true
          "never"
        }
      }

    assertSame(failure, thrown)
    assertTrue(thrown.suppressed.any { it === releaseFailure })
    assertEquals(listOf(Triple("parent-1", "test-owner-token", 1L)), store.releaseCalls)
    assertEquals(1L, store.executionLeaseValue?.generation)
    assertFalse(bodyRan)
  }

  @Test
  fun `hook registration failure stops heartbeat releases lease and does not run the goal body`() {
    val store = InMemoryExecutionLeaseStore(null)
    val failure = IllegalStateException("register hook")
    val supervisor = FakeGoalSupervisor(NOT_RUNNING)
    val coordinator =
      DefaultGoalRunnerExecutionCoordinator(
        manifestStore = store,
        supervisor = supervisor,
        clock = fixedClock(),
        shutdownHookPort = FailingRegisterShutdownHookPort(failure),
        daemonThreadPort = FakeDaemonThreadPort,
        identifierGeneratorPort = FakeIdentifierGeneratorPort,
      )
    var bodyRan = false

    val thrown =
      assertFailsWith<IllegalStateException> {
        coordinator.runOwned("parent-1") {
          bodyRan = true
          "never"
        }
      }

    assertEquals("register hook", thrown.message)
    assertNull(store.executionLeaseValue)
    assertTrue(supervisor.heartbeatStopped)
    assertFalse(bodyRan)
  }

  @Test
  fun `goal body failure stays primary when hook unregister fails during teardown`() {
    val store = InMemoryExecutionLeaseStore(null)
    val primary = IllegalArgumentException("body failed")
    val secondary = IllegalStateException("unregister hook")
    val supervisor = FakeGoalSupervisor(NOT_RUNNING)
    val coordinator =
      DefaultGoalRunnerExecutionCoordinator(
        manifestStore = store,
        supervisor = supervisor,
        clock = fixedClock(),
        shutdownHookPort = FailingUnregisterShutdownHookPort(secondary),
        daemonThreadPort = FakeDaemonThreadPort,
        identifierGeneratorPort = FakeIdentifierGeneratorPort,
      )

    val thrown =
      assertFailsWith<IllegalArgumentException> {
        coordinator.runOwned("parent-1") { throw primary }
      }

    assertEquals("body failed", thrown.message)
    assertTrue(thrown.suppressed.any { it === secondary })
    assertNull(store.executionLeaseValue)
    assertTrue(supervisor.heartbeatStopped)
  }

  @Test
  fun `goal body cancellation stays primary when lease teardown fails`() {
    val store = InMemoryExecutionLeaseStore(null)
    val primary = CancellationException("body cancelled")
    val secondary = IllegalStateException("release failed")
    store.releaseFailure = secondary
    val coordinator = testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))

    val thrown =
      assertFailsWith<CancellationException> {
        coordinator.runOwned("parent-1") { throw primary }
      }

    assertSame(primary, thrown)
    assertTrue(thrown.suppressed.any { it === secondary })
    assertTrue(store.releaseCalls.isNotEmpty())
  }

  @Test
  fun `heartbeat stop failure still releases the execution lease`() {
    val store = InMemoryExecutionLeaseStore(null)
    val failure = IllegalStateException("stop heartbeat")
    val supervisor = FakeGoalSupervisor(NOT_RUNNING, heartbeatStopFailure = failure)
    val coordinator = testCoordinator(store, supervisor)

    val thrown =
      assertFailsWith<IllegalStateException> {
        coordinator.runOwned("parent-1") { "done" }
      }

    assertEquals("stop heartbeat", thrown.message)
    assertNull(store.executionLeaseValue)
  }

  @Test
  fun `successful goal body does not return when lease release fails`() {
    val store = InMemoryExecutionLeaseStore(null)
    store.releaseFailure = IllegalStateException("release failed")
    val coordinator = testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))

    assertFailsWith<IllegalStateException> {
      coordinator.runOwned("parent-1") { "done" }
    }
  }

  @Test
  fun `successful nullable goal body preserves its null result`() {
    val store = InMemoryExecutionLeaseStore(null)
    val coordinator = testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))

    val result = coordinator.runOwned<String?>("parent-1") { null }

    assertEquals(GoalRunnerOwnedRun.Completed(null), result)
    assertNull(store.executionLeaseValue)
  }

  @Test
  fun `a parent heartbeat tick that lost fencing fails the goal instead of reporting success`() {
    val store = InMemoryExecutionLeaseStore(lease(generation = 1, ownerToken = "old-owner"))
    val supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)
    val coordinator = testCoordinator(store, supervisor)

    val failure =
      assertIs<GoalRunnerOwnedRun.AlreadyRunning>(
        coordinator.runOwned("parent-1") {
          store.executionLeaseValue = lease(generation = 9, ownerToken = "usurper-owner")
          assertTrue(supervisor.runHeartbeatTick() is FeatureTaskRuntimeHeartbeatTick.FencingLost)
        },
      )

    assertTrue(failure.reason.contains("execution lease fencing was lost"))
  }
}

private const val PARENT_LEASE_SECONDS = 30L

class GoalRunnerShutdownHookTest {
  @Test
  fun `the hook records a runner interruption with the injected clock in one write`() {
    val store = InMemoryExecutionLeaseStore(null)
    val coordinator = testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))

    coordinator.recordInterruption("parent-1")

    assertEquals(1, store.pauseNowCalls.size)
    assertEquals("runner_interrupted", store.pauseNowCalls.single().first)
    assertEquals("2026-08-02T10:00:00Z", store.pauseNowCalls.single().second)
    assertEquals(false, store.pauseNowCalls.single().third)
    assertTrue(store.controlStateValue.paused)
    assertEquals("2026-08-02T10:00:00Z", store.controlStateValue.pausedAt)
  }

  @Test
  fun `an interruption reason is distinguishable from an operator stop`() {
    val store = InMemoryExecutionLeaseStore(null)
    testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))
      .recordInterruption("parent-1")

    assertEquals("runner_interrupted", store.controlStateValue.pauseReason)
    assertTrue(store.controlStateValue.pauseReason != "operator_stop")
  }

  @Test
  fun `the hook leaves a stop-verb reason alone and performs no second write`() {
    val store = InMemoryExecutionLeaseStore(null)
    store.controlStateValue =
      GoalRunnerControlState(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = "operator_stop",
        pausedAt = "2026-08-02T09:00:00Z",
      )

    testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))
      .recordInterruption("parent-1")

    assertEquals("operator_stop", store.controlStateValue.pauseReason)
    assertEquals("2026-08-02T09:00:00Z", store.controlStateValue.pausedAt)
  }

  @Test
  fun `a blocked durable write cannot stall shutdown past the budget`() {
    val store = InMemoryExecutionLeaseStore(null)
    store.pauseNowBlocksForever = true
    val coordinator = testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))

    val elapsed = measureTimeMillis { coordinator.recordInterruption("parent-1") }

    assertTrue(elapsed < SHUTDOWN_BUDGET_CEILING_MILLIS, "hook took ${elapsed}ms")
  }

  @Test
  fun `a throwing durable write never escapes the hook`() {
    val store = InMemoryExecutionLeaseStore(null)
    store.pauseNowFailure = { error("database is gone") }
    val coordinator = testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))

    coordinator.recordInterruption("parent-1")

    assertEquals(1, store.pauseNowCalls.size)
    assertFalse(store.controlStateValue.paused)
  }

  @Test
  fun `a normally completed owned run leaves no hook behind to write on exit`() {
    val store = InMemoryExecutionLeaseStore(null)
    val coordinator = testCoordinator(store, FakeGoalSupervisor(NOT_RUNNING))

    coordinator.runOwned("parent-1") { "done" }

    assertEquals(emptyList(), store.pauseNowCalls)
  }
}

private val NOT_RUNNING = FeatureTaskRuntimeProcessInspection.NotRunning
private const val SHUTDOWN_BUDGET_CEILING_MILLIS = 10_000L

private class InMemoryExecutionLeaseStore(
  initialLease: GoalRunnerExecutionLease?,
) : GoalRunnerManifestStoreDefaults() {
  var executionLeaseValue: GoalRunnerExecutionLease? = initialLease
  var controlStateValue: GoalRunnerControlState = GoalRunnerControlState()
  val pauseNowCalls: MutableList<Triple<String, String, Boolean>> = mutableListOf()
  val releaseCalls: MutableList<Triple<String, String, Long>> = mutableListOf()
  var persistControlStateCalls: Int = 0
  var pauseNowFailure: (() -> Nothing)? = null
  var pauseNowBlocksForever: Boolean = false
  var releaseFailure: Throwable? = null

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState = controlStateValue

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState {
    persistControlStateCalls += 1
    controlStateValue = state
    return state
  }

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
  ): GoalRunnerControlState {
    pauseNowCalls.add(Triple(reason, pausedAt, overwriteExistingReason))
    if (pauseNowBlocksForever) Thread.sleep(Long.MAX_VALUE)
    pauseNowFailure?.invoke()
    if (controlStateValue.paused && !overwriteExistingReason) return controlStateValue
    controlStateValue =
      controlStateValue.copy(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = reason,
        pausedAt = pausedAt,
      )
    return controlStateValue
  }

  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? = executionLeaseValue

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean {
    if (executionLeaseValue?.ownerToken != expectedOwnerToken) return false
    executionLeaseValue = lease
    return true
  }

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean {
    if (executionLeaseValue?.ownerToken != lease.ownerToken || executionLeaseValue?.generation != lease.generation) {
      return false
    }
    executionLeaseValue = lease
    return true
  }

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean {
    releaseCalls += Triple(parentWorkflowId, ownerToken, generation)
    releaseFailure?.let { throw it }
    if (executionLeaseValue?.ownerToken != ownerToken || executionLeaseValue?.generation != generation) return false
    executionLeaseValue = null
    return true
  }
}

private class FakeGoalSupervisor(
  initialInspection: FeatureTaskRuntimeProcessInspection,
  private val current: FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("host", "boot", 200, "birth-200"),
  private val markNotRunningAfterAwait: Boolean = true,
  private val heartbeatStartFailure: Throwable? = null,
  private val heartbeatStopFailure: Throwable? = null,
) : FeatureTaskRuntimeWorkerSupervisor {
  private var inspection = initialInspection
  private var tick: (() -> FeatureTaskRuntimeHeartbeatTick)? = null
  private var fencingLostReason: String? = null
  var awaitExitCalls: Int = 0
    private set
  var lastAwaitTimeout: Duration? = null
    private set
  var capturedPlan: FeatureTaskRuntimeHeartbeatPlan? = null
    private set
  var heartbeatStopped: Boolean = false
    private set

  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity = current

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection = inspection

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) {
    awaitExitCalls += 1
    lastAwaitTimeout = timeout
    if (markNotRunningAfterAwait) {
      inspection = FeatureTaskRuntimeProcessInspection.NotRunning
    }
  }

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat {
    heartbeatStartFailure?.let { throw it }
    capturedPlan = plan
    tick = heartbeat
    return object : FeatureTaskRuntimeHeartbeat {
      override fun stop() {
        heartbeatStopFailure?.let { throw it }
        heartbeatStopped = true
      }

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

private fun testCoordinator(
  store: InMemoryExecutionLeaseStore,
  supervisor: FakeGoalSupervisor,
  clock: Clock = fixedClock(),
) = DefaultGoalRunnerExecutionCoordinator(
  manifestStore = store,
  supervisor = supervisor,
  clock = clock,
  shutdownHookPort = FakeShutdownHookPort,
  daemonThreadPort = FakeDaemonThreadPort,
  identifierGeneratorPort = FakeIdentifierGeneratorPort,
)

private object FakeShutdownHookPort : ShutdownHookPort {
  override fun register(action: () -> Unit): ShutdownHookRegistration =
    object : ShutdownHookRegistration {
      override fun unregister(): Boolean = true
    }
}

private class FailingRegisterShutdownHookPort(
  private val failure: Throwable,
) : ShutdownHookPort {
  override fun register(action: () -> Unit): ShutdownHookRegistration {
    throw failure
  }
}

private class FailingUnregisterShutdownHookPort(
  private val failure: Throwable,
) : ShutdownHookPort {
  override fun register(action: () -> Unit): ShutdownHookRegistration =
    object : ShutdownHookRegistration {
      override fun unregister(): Boolean {
        throw failure
      }
    }
}

private object FakeDaemonThreadPort : DaemonThreadPort {
  override fun runWithJoinBudget(
    action: () -> Unit,
    joinBudgetMillis: Long,
  ) {
    val worker = Thread(action)
    worker.isDaemon = true
    worker.start()
    worker.join(joinBudgetMillis.coerceAtLeast(1L))
    if (worker.isAlive) {
      worker.interrupt()
      worker.join(1_000L)
    }
  }
}

private object FakeIdentifierGeneratorPort : IdentifierGeneratorPort {
  override fun randomToken(): String = "test-owner-token"
}

internal fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneOffset.UTC)

private fun recentBirthToken(): String = Instant.parse("2026-08-02T09:59:59Z").toEpochMilli().toString()

private fun staleBirthToken(): String = Instant.parse("2026-08-02T09:54:00Z").toEpochMilli().toString()

private const val EXPIRED_LEASE_EXPIRES_AT = "2026-08-02T09:59:30Z"

internal fun lease(
  generation: Long,
  ownerToken: String,
  pid: Long = 100,
  processBirthToken: String = "birth-100",
  expiresAt: String = "2026-08-02T10:00:30Z",
) = GoalRunnerExecutionLease(
  generation = generation,
  ownerToken = ownerToken,
  hostIdentity = "host",
  bootIdentity = "boot",
  pid = pid,
  processBirthToken = processBirthToken,
  heartbeatAt = "2026-08-02T09:59:00Z",
  expiresAt = expiresAt,
)
