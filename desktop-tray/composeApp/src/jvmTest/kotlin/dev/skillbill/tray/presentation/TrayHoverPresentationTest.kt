package dev.skillbill.tray.presentation

import dev.skillbill.tray.application.DashboardState
import dev.skillbill.tray.core.domain.model.*
import dev.skillbill.tray.core.testing.board
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.ui.durationText
import dev.skillbill.tray.ui.phaseElapsedText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TrayHoverPresentationTest {
  @Test
  fun `active validation stays visible beyond the first phase detail page`() {
    val phases = (1..10).map { WorkPhase("phase-$it", "complete", 1, true) }
    val pipeline = phases.map { it.id } + listOf("validate", "commit_push")
    val work = item("goal", "feature-goal").copy(currentPhaseId = "validate",
      phaseExecution = PhaseExecution("validate", PhaseExecutionKind.ATTEMPT, 1, null))
    val detail = Detail(work, pipeline, emptyList(), null, "validate", 10, 2, 0, null, null,
      emptyMap(), phases = phases)
    val visible = phasePipeline(detail)
    assertEquals(pipeline, visible.map { it.id })
    assertEquals(WorkPhase("validate", "in_progress", 1, false), visible[10])
    assertEquals(phases, visible.take(10))
    val completed = WorkPhase("validate", "complete", 2, true)
    assertEquals(completed, phasePipeline(detail.copy(phases = phases + completed))[10])
  }

  @Test
  fun `detail-only staleness freezes goal and standalone elapsed values`() {
    val now = Instant.parse("2026-10-11T10:10:00Z")
    val item = item("stale-goal", "feature-goal").copy(
      startedAt = now - 120.seconds,
      updatedAt = now - 30.seconds,
      activeDurationMs = 30_000,
      activeDurationAsOf = now - 30.seconds,
    )
    val stale = effectiveFreshness(item, boardStale = false, detailExtrasStale = true) == Freshness.STALE
    assertEquals("2m 0s", durationText(30_000, now - 30.seconds, item, now, stale, frozen = 120_000))
    assertEquals("30s", durationText(30_000, now - 30.seconds, item, now, stale))

    val phase = item.copy(scope = ExecutionScope.STANDALONE_PHASE)
    assertEquals("1m 30s", phaseElapsedText(phase, now, stale))
  }

  @Test
  fun `hover limits each category to four and escapes repository markup`() {
    val items = (0..4).flatMap { index ->
      listOf(item("GOAL-$index", "feature-goal"), item("PHASE-$index", "feature-task-runtime"))
    }
    val text = presentTrayHover(DashboardState(AppSettings(), board = board(items)))
    assertContains(text, "GOAL-3")
    assertContains(text, "PHASE-3")
    assertFalse("GOAL-4" in text)
    assertFalse("PHASE-4" in text)
    assertContains(text, "&lt;repo&gt;")
    assertFalse("<repo>" in text)
    assertContains(text, "#3FB950")
  }

  private fun item(id: String, family: String): WorkItem {
    val goal = family == "feature-goal"
    val section = if (goal) WorkSection.GOALS else WorkSection.PHASES
    return workItem(id, family = if (goal) WorkFamily.FEATURE_GOAL else WorkFamily.FEATURE_TASK_RUNTIME, issueKey = id).copy(
      identity = WorkIdentity(id, "repo"), repositoryRoot = "/work/<repo>", branch = null, summary = "work",
      liveSection = section, displayGroup = section,
    )
  }
}
