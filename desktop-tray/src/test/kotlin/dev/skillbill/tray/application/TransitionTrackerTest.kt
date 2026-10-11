package dev.skillbill.tray.application

import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant

class TransitionTrackerTest {
  @Test
  fun `first observation is baseline and only five enabled lifecycle transitions notify`() {
    val tracker = TransitionTracker()
    val initial = listOf(
      item("blocked", Lifecycle.ACTIVE, WorkFamily.FEATURE_GOAL),
      item("failed", Lifecycle.ACTIVE, WorkFamily.FEATURE_GOAL),
      item("done", Lifecycle.ACTIVE, WorkFamily.FEATURE_GOAL),
      item("paused", Lifecycle.ACTIVE, WorkFamily.FEATURE_GOAL),
      item("phase", Lifecycle.ACTIVE, WorkFamily.FEATURE_VERIFY).copy(scope = ExecutionScope.STANDALONE_PHASE),
    )
    tracker.observe(board(initial))
    assertEquals(emptyList(), tracker.observe(board(initial)))
    val staleChange = initial[0].copy(lifecycle = Lifecycle.BLOCKED, freshness = Freshness.STALE)
    assertEquals(emptyList(), tracker.observe(board(listOf(staleChange))))
    val changes = listOf(
      initial[0].copy(lifecycle = Lifecycle.BLOCKED),
      initial[1].copy(lifecycle = Lifecycle.FAILED),
      initial[2].copy(lifecycle = Lifecycle.TERMINAL),
      initial[3].copy(lifecycle = Lifecycle.PAUSED),
      initial[4].copy(lifecycle = Lifecycle.TERMINAL),
    )
    val events = tracker.observe(board(changes))
    assertEquals(listOf("blocked", "failed", "goal_completed", "pause_took_effect", "standalone_phase_finished"), events.map { it.second })
    val settings = AppSettings(notifications = defaultNotifications.mapValues { (key, _) -> key != "failed" })
    assertEquals(listOf("blocked", "goal_completed", "pause_took_effect", "standalone_phase_finished"), enabledTransitions(events, settings).map { it.second })
  }

  private fun board(items: List<WorkItem>) = Board(Instant.parse("2026-10-11T10:00:00Z"), Retention.DAY, items)
  private fun item(id: String, lifecycle: Lifecycle, family: WorkFamily) = WorkItem(
    identity = WorkIdentity(id, "repo-$id"),
    workflowId = id,
    invocationId = null,
    repositoryRoot = "/repo/$id",
    branch = "main",
    issueKey = "SKILL-$id",
    family = family,
    scope = ExecutionScope.WORKFLOW,
    phaseId = null,
    activity = null,
    lifecycle = lifecycle,
    freshness = Freshness.FRESH,
    summary = "summary",
    currentStepId = "implement",
    currentStepLabel = "Implement",
    progressCompleted = null,
    progressTotal = null,
    activeDurationMs = null,
    activeDurationAsOf = null,
    subtaskActiveDurationMs = null,
    subtaskActiveDurationAsOf = null,
    pauseRequested = false,
    pauseReasonCode = null,
    pauseReason = null,
    planningState = null,
    plannedSubtasks = null,
    totalSubtasks = null,
    planningWaveSubtaskIds = emptyList(),
    executionCount = null,
    executionTotal = null,
    updatedAt = Instant.parse("2026-10-11T10:00:00Z"),
    finishedAt = null,
    finishedAtSource = null,
  )
}
