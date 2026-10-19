package skillbill.engine.goalrunner.status

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.sqlite.withGoalRunnerControlRepository
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalRunnerExecutionLeaseTest {
  @Test
  fun `parent execution lease survives a reopened database`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-execution-lease").resolve("metrics.db")
    val lease = leaseFixture(generation = 1, ownerToken = "owner-token-123456")

    withGoalRunnerControlRepository(dbPath) { store ->
      assertTrue(store.acquireExecutionLease("parent-lease", lease))
      assertEquals(lease, store.executionLease("parent-lease"))
      assertTrue(
        store.heartbeatExecutionLease(
          "parent-lease",
          lease.copy(
            heartbeatAt = Instant.parse("2026-08-02T10:00:10Z"),
            expiresAt = Instant.parse("2026-08-02T10:00:40Z"),
          ),
        ),
      )
    }

    withGoalRunnerControlRepository(dbPath) { store ->
      assertEquals(Instant.parse("2026-08-02T10:00:40Z"), store.executionLease("parent-lease")?.expiresAt)
      store.persistControlState(
        "parent-lease",
        GoalRunnerControlState(
          pauseRequested = true,
          pauseReason = "operator_request",
          executionLease = requireNotNull(store.executionLease("parent-lease")),
        ),
      )
      store.clearControlState("parent-lease")
      assertEquals(
        GoalRunnerControlState(
          executionLease =
            lease.copy(
              heartbeatAt = Instant.parse("2026-08-02T10:00:10Z"),
              expiresAt = Instant.parse("2026-08-02T10:00:40Z"),
            ),
        ),
        store.controlState("parent-lease"),
      )
      assertTrue(store.releaseExecutionLease("parent-lease", lease.ownerToken, lease.generation))
      assertEquals(null, store.executionLease("parent-lease"))
    }
  }

  @Test
  fun `expired execution lease releases only at the expiry boundary with matching fencing`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-expired-lease-release").resolve("metrics.db")
    val lease = leaseFixture(generation = 3, ownerToken = "owner-token-expired")

    withGoalRunnerControlRepository(dbPath) { store ->
      assertTrue(store.acquireExecutionLease("parent-expired", lease))
      assertFalse(
        store.releaseExecutionLeaseIfExpired(
          "parent-expired",
          lease.ownerToken,
          lease.generation,
          "2026-08-02T10:00:29Z",
        ),
      )
      assertFalse(
        store.releaseExecutionLeaseIfExpired(
          "parent-expired",
          "different-owner",
          lease.generation,
          "2026-08-02T10:00:30Z",
        ),
      )
      assertTrue(
        store.releaseExecutionLeaseIfExpired(
          "parent-expired",
          lease.ownerToken,
          lease.generation,
          "2026-08-02T10:00:30Z",
        ),
      )
      assertEquals(null, store.executionLease("parent-expired"))
    }
  }

  @Test
  fun `clearing control state preserves the accumulated execution clock`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-clear-active-clock").resolve("metrics.db")
    val lease = leaseFixture(generation = 1, ownerToken = "owner-token-123456")

    withGoalRunnerControlRepository(dbPath) { store ->
      assertTrue(store.acquireExecutionLease("parent-clear", lease))
      assertTrue(
        store.heartbeatExecutionLease(
          "parent-clear",
          lease.renewedAt(Instant.parse("2026-08-02T10:00:10Z")),
        ),
      )
      val paused = store.controlState("parent-clear")
      store.persistControlState(
        "parent-clear",
        paused.copy(
          pauseRequested = true,
          paused = true,
          pauseReason = "operator_request",
          pausedAt = "2026-08-02T10:00:10Z",
        ),
      )

      store.clearControlState("parent-clear")

      val cleared = store.controlState("parent-clear")
      assertEquals(false, cleared.paused)
      assertEquals(10_000, cleared.activeDurationMs)
      assertEquals("2026-08-02T10:00:10Z", cleared.activeDurationAsOf)
    }
  }

  @Test
  fun `the active execution clock caps an over-long heartbeat gap instead of counting the downtime`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-active-clock").resolve("metrics.db")
    val lease = augustSeventhLeaseFixture()

    withGoalRunnerControlRepository(dbPath) { store ->
      assertTrue(store.acquireExecutionLease("parent-clock", lease))
      assertEquals(0, store.controlState("parent-clock").activeDurationMs)

      assertTrue(
        store.heartbeatExecutionLease(
          "parent-clock",
          lease.renewedAt(Instant.parse("2026-08-07T18:22:10Z")),
        ),
      )
      assertTrue(
        store.heartbeatExecutionLease(
          "parent-clock",
          lease.renewedAt(Instant.parse("2026-08-07T18:22:20Z")),
        ),
      )
      assertEquals(20_000, store.controlState("parent-clock").activeDurationMs)

      assertTrue(
        store.heartbeatExecutionLease(
          "parent-clock",
          lease.renewedAt(Instant.parse("2026-08-07T18:22:45Z")),
        ),
      )
      assertEquals(40_000, store.controlState("parent-clock").activeDurationMs)

      assertTrue(
        store.heartbeatExecutionLease(
          "parent-clock",
          lease.renewedAt(Instant.parse("2026-08-07T18:22:55Z")),
        ),
      )
      assertEquals(50_000, store.controlState("parent-clock").activeDurationMs)
    }
  }

  @Test
  fun `a heartbeat slightly past the limit is credited one interval rather than discarded`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-late-heartbeat").resolve("metrics.db")
    val lease = augustSeventhLeaseFixture()

    withGoalRunnerControlRepository(dbPath) { store ->
      assertTrue(store.acquireExecutionLease("parent-late", lease))
      assertTrue(
        store.heartbeatExecutionLease(
          "parent-late",
          lease.renewedAt(Instant.parse("2026-08-07T18:22:20.001Z")),
        ),
      )
      assertEquals(20_000, store.controlState("parent-late").activeDurationMs)
    }
  }

  @Test
  fun `reacquiring a lease after downtime resumes the clock without counting the downtime`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-active-clock-reacquire").resolve("metrics.db")
    val lease = augustSeventhLeaseFixture()

    withGoalRunnerControlRepository(dbPath) { store ->
      assertTrue(store.acquireExecutionLease("parent-reacquire", lease))
      assertTrue(
        store.heartbeatExecutionLease(
          "parent-reacquire",
          lease.renewedAt(Instant.parse("2026-08-07T18:22:10Z")),
        ),
      )
      assertTrue(store.releaseExecutionLease("parent-reacquire", lease.ownerToken, lease.generation))

      val nextDay =
        lease.copy(
          generation = 2,
          heartbeatAt = Instant.parse("2026-08-08T06:31:00Z"),
          expiresAt = Instant.parse("2026-08-08T06:31:30Z"),
        )
      assertTrue(store.acquireExecutionLease("parent-reacquire", nextDay))
      assertEquals(10_000, store.controlState("parent-reacquire").activeDurationMs)

      assertTrue(
        store.heartbeatExecutionLease(
          "parent-reacquire",
          nextDay.renewedAt(Instant.parse("2026-08-08T06:31:05Z")),
        ),
      )
      assertEquals(15_000, store.controlState("parent-reacquire").activeDurationMs)
    }
  }

  @Test
  fun `heartbeat increments goal and subtask active duration equally`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-subtask-dual").resolve("metrics.db")
    val lease = augustSeventhLeaseFixture()

    withGoalRunnerControlRepository(dbPath) { store ->
      store.persistControlState("parent-dual", GoalRunnerControlState(currentSubtaskId = 1))
      assertTrue(store.acquireExecutionLease("parent-dual", lease))
      assertTrue(
        store.heartbeatExecutionLease(
          "parent-dual",
          lease.renewedAt(Instant.parse("2026-08-07T18:22:10Z")),
        ),
      )
      val state = store.controlState("parent-dual")
      assertEquals(10_000, state.activeDurationMs)
      assertEquals(state.activeDurationMs, state.subtaskActiveDurationMs)
    }
  }

  @Test
  fun `clearing control state preserves both goal and subtask accumulated clocks`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-clear-subtask-clock").resolve("metrics.db")
    val lease = leaseFixture(generation = 1, ownerToken = "owner-token-123456")

    withGoalRunnerControlRepository(dbPath) { store ->
      store.persistControlState(
        "parent-clear-subtask",
        GoalRunnerControlState(currentSubtaskId = 1),
      )
      assertTrue(store.acquireExecutionLease("parent-clear-subtask", lease))
      assertTrue(
        store.heartbeatExecutionLease(
          "parent-clear-subtask",
          lease.renewedAt(Instant.parse("2026-08-02T10:00:10Z")),
        ),
      )
      store.persistControlState(
        "parent-clear-subtask",
        store.controlState("parent-clear-subtask").copy(
          pauseRequested = true,
          paused = true,
          pauseReason = "operator_request",
          pausedAt = "2026-08-02T10:00:10Z",
        ),
      )

      store.clearControlState("parent-clear-subtask")

      val cleared = store.controlState("parent-clear-subtask")
      assertEquals(10_000, cleared.activeDurationMs)
      assertEquals(10_000, cleared.subtaskActiveDurationMs)
      assertEquals("2026-08-02T10:00:10Z", cleared.activeDurationAsOf)
      assertEquals("2026-08-02T10:00:10Z", cleared.subtaskActiveDurationAsOf)
    }
  }

  private fun GoalRunnerExecutionLease.renewedAt(heartbeatAt: Instant): GoalRunnerExecutionLease =
    copy(heartbeatAt = heartbeatAt, expiresAt = heartbeatAt.plusSeconds(30))

  private fun leaseFixture(
    generation: Long,
    ownerToken: String,
  ): GoalRunnerExecutionLease =
    GoalRunnerExecutionLease(
      generation = generation,
      ownerToken = ownerToken,
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 42,
      processBirthToken = "birth",
      heartbeatAt = Instant.parse("2026-08-02T10:00:00Z"),
      expiresAt = Instant.parse("2026-08-02T10:00:30Z"),
    )

  private fun augustSeventhLeaseFixture(): GoalRunnerExecutionLease =
    leaseFixture(generation = 1, ownerToken = "owner-token-123456").copy(
      heartbeatAt = Instant.parse("2026-08-07T18:22:00Z"),
      expiresAt = Instant.parse("2026-08-07T18:22:30Z"),
    )
}
