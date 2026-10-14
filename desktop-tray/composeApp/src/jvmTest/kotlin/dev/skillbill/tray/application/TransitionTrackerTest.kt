package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.*
import dev.skillbill.tray.core.testing.board
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class TransitionTrackerTest {
  @Test
  fun `first observation is baseline and only five enabled lifecycle transitions notify`() {
    val tracker = TransitionTracker()
    val initial = listOf(
      workItem("blocked"),
      workItem("failed"),
      workItem("done"),
      workItem("paused"),
      workItem("phase", family = WorkFamily.FEATURE_VERIFY).copy(scope = ExecutionScope.STANDALONE_PHASE),
    )
    tracker.observe(board(initial))
    assertEquals(emptyList(), tracker.observe(board(initial)))
    val staleChange = initial[0].copy(lifecycle = Lifecycle.BLOCKED, freshness = Freshness.STALE)
    assertEquals(emptyList(), tracker.observe(board(listOf(staleChange))))
    val changes = listOf(
      initial[0].copy(lifecycle = Lifecycle.BLOCKED, updatedAt = initial[0].updatedAt + 1.seconds),
      initial[1].copy(lifecycle = Lifecycle.FAILED, updatedAt = initial[1].updatedAt + 1.seconds),
      initial[2].copy(lifecycle = Lifecycle.TERMINAL, updatedAt = initial[2].updatedAt + 1.seconds),
      initial[3].copy(lifecycle = Lifecycle.PAUSED, updatedAt = initial[3].updatedAt + 1.seconds),
      initial[4].copy(lifecycle = Lifecycle.TERMINAL, updatedAt = initial[4].updatedAt + 1.seconds),
    )
    val events = tracker.observe(board(changes))
    assertEquals(listOf("blocked", "failed", "goal_completed", "pause_took_effect", "standalone_phase_finished"), events.map { it.second })
    val settings = AppSettings(notifications = defaultNotifications.mapValues { (key, _) -> key != "failed" })
    assertEquals(listOf("blocked", "goal_completed", "pause_took_effect", "standalone_phase_finished"), enabledTransitions(events, settings).map { it.second })
  }

  @Test
  fun `delayed live section cannot replay a completion from a newer recent section`() {
    val tracker = TransitionTracker()
    val active = workItem("goal").copy(databaseStoreId = "store", databaseRevision = 10)
    val completed = active.copy(lifecycle = Lifecycle.TERMINAL, databaseRevision = 12)
    tracker.observe(board(listOf(active)))
    assertEquals(listOf("goal_completed"), tracker.observe(board(listOf(completed))).map { it.second })
    assertEquals(emptyList(), tracker.observe(board(listOf(active.copy(databaseRevision = 11)))))
    assertEquals(emptyList(), tracker.observe(board(listOf(completed))))
  }

  @Test
  fun `reconnect baseline absorbs completions while disconnected`() {
    val tracker = TransitionTracker()
    val active = workItem("goal")
    val completed = active.copy(lifecycle = Lifecycle.TERMINAL, updatedAt = active.updatedAt + 1.seconds)
    tracker.baseline(board(listOf(active)))
    tracker.baseline(board(listOf(completed)))
    assertEquals(emptyList(), tracker.observe(board(listOf(completed))))

    val newActive = workItem("new-goal")
    tracker.baseline(board(listOf(completed, newActive)))
    val newCompletion = newActive.copy(lifecycle = Lifecycle.TERMINAL, updatedAt = newActive.updatedAt + 1.seconds)
    assertEquals(listOf("goal_completed"), tracker.observe(board(listOf(completed, newCompletion))).map { it.second })
  }
}
