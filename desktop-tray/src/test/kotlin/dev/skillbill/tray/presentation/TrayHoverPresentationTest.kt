package dev.skillbill.tray.presentation

import dev.skillbill.tray.application.DashboardState
import dev.skillbill.tray.domain.AppSettings
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import dev.skillbill.tray.ui.durationText
import dev.skillbill.tray.ui.phaseElapsedText
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrayHoverPresentationTest {
  @Test
  fun `detail-only staleness freezes goal and standalone elapsed values`() {
    val now = Instant.parse("2026-10-11T10:10:00Z")
    val item = item("stale-goal", "feature-goal").copy(
      startedAt = now.minusSeconds(120),
      updatedAt = now.minusSeconds(30),
      activeDurationMs = 30_000,
      activeDurationAsOf = now.minusSeconds(30),
    )
    val stale = effectiveFreshness(item, boardStale = false, detailExtrasStale = true) == Freshness.STALE
    assertEquals("2m 0s", durationText(30_000, now.minusSeconds(30), item, now, stale, frozen = 120_000))

    val phase = item.copy(scope = ExecutionScope.STANDALONE_PHASE)
    assertEquals("1m 30s", phaseElapsedText(phase, now, stale))
  }

  @Test
  fun `hover limits each category to four and escapes repository markup`() {
    val items = (0..4).flatMap { index ->
      listOf(item("GOAL-$index", "feature-goal"), item("PHASE-$index", "feature-task-runtime"))
    }
    val board = Board(Instant.parse("2026-10-11T10:00:00Z"), Retention.DAY, items)
    val text = presentTrayHover(DashboardState(AppSettings(), board = board))
    assertContains(text, "GOAL-3")
    assertContains(text, "PHASE-3")
    assertFalse("GOAL-4" in text)
    assertFalse("PHASE-4" in text)
    assertContains(text, "&lt;repo&gt;")
    assertFalse("<repo>" in text)
    assertContains(text, "#3FB950")
  }

  private fun item(id: String, family: String): WorkItem = WorkItem(
    WorkIdentity(id, "repo"), id, null, "/work/<repo>", null, id,
    if (family == "feature-goal") WorkFamily.FEATURE_GOAL else WorkFamily.FEATURE_TASK_RUNTIME,
    ExecutionScope.WORKFLOW, null, null, Lifecycle.ACTIVE, Freshness.FRESH, "work", "implement", "Implement",
    null, null, activeDurationMs = null, activeDurationAsOf = null, subtaskActiveDurationMs = null,
    subtaskActiveDurationAsOf = null, pauseRequested = false, pauseReasonCode = null, pauseReason = null,
    planningState = null, plannedSubtasks = null, totalSubtasks = null, planningWaveSubtaskIds = emptyList(),
    executionCount = null, executionTotal = null, updatedAt = Instant.parse("2026-10-11T10:00:00Z"),
    finishedAt = null, finishedAtSource = null,
    liveSection = if (family == "feature-goal") WorkSection.GOALS else WorkSection.PHASES,
    displayGroup = if (family == "feature-goal") WorkSection.GOALS else WorkSection.PHASES,
  )
}
