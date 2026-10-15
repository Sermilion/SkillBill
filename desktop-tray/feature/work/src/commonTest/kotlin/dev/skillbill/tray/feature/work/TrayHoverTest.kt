package dev.skillbill.tray.feature.work

import dev.skillbill.tray.core.domain.model.BoardArrangement
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.TrayStatus
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.TrackedSections
import dev.skillbill.tray.core.ui.loadedList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrayHoverTest {
  private fun live(id: String, goal: Boolean): WorkItem {
    val section = if (goal) WorkSection.GOALS else WorkSection.PHASES
    return workItem(id, family = if (goal) WorkFamily.FEATURE_GOAL else WorkFamily.FEATURE_TASK_RUNTIME, issueKey = id).copy(
      identity = WorkIdentity(id, "repo"),
      repositoryRoot = "/work/<repo>",
      branch = null,
      liveSection = section,
    )
  }

  private fun stateOf(goals: List<WorkItem>, phases: List<WorkItem>) = TrayUiState(
    sections = TrackedSections(goals = loadedList(goals, hasMore = false), phases = loadedList(phases, hasMore = false)),
    arrangement = BoardArrangement(
      trayStatus = TrayStatus.RUNNING,
      running = goals.size + phases.size,
      needsYou = 0,
      doneWithinDay = 0,
      goals = goals.map { it.identity },
      livePhases = phases.map { it.identity },
      finishedPhases = emptyList(),
    ),
  )

  @Test
  fun `the hover limits each category to four and escapes repository markup`() {
    val text = stateOf((0..4).map { live("GOAL-$it", goal = true) }, (0..4).map { live("PHASE-$it", goal = false) }).trayHover()

    assertContains(text, "GOAL-3")
    assertContains(text, "PHASE-3")
    assertFalse("GOAL-4" in text)
    assertFalse("PHASE-4" in text)
    assertContains(text, "&lt;repo&gt;")
    assertFalse("<repo>" in text)
    assertContains(text, "#3FB950")
  }

  @Test
  fun `the hover says loading until a live section has been read`() {
    assertEquals("Loading your work…", TrayUiState().trayHover())
  }

  @Test
  fun `the hover says so when the board on screen could not be refreshed`() {
    val stale = stateOf(listOf(live("GOAL-1", goal = true)), emptyList())
      .let { it.copy(sections = it.sections.copy(phases = PagedListState.InitialError(Problem(ProblemCode.DATABASE_ACCESS, "Unreadable")))) }

    assertContains(stale.trayHover(), "Showing stale work data")
  }
}
