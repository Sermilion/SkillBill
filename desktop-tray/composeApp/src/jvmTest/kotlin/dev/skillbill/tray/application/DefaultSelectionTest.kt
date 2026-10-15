package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.loadedList
import dev.skillbill.tray.feature.history.HistoryUiState
import dev.skillbill.tray.feature.work.WorkUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultSelectionTest {
  private val problem = Problem(ProblemCode.DATABASE_ACCESS, "Unreadable")
  private val none: PagedListState<WorkItem> = loadedList(emptyList(), hasMore = false)
  private val finished = HistoryUiState(list = loaded(workItem("done-1"), workItem("done-2")))

  private fun loaded(vararg items: WorkItem): PagedListState<WorkItem> = loadedList(items.toList(), hasMore = false)

  @Test
  fun `nothing is chosen until every live section has been read`() {
    val loading = WorkUiState(needsYou = none, goals = PagedListState.InitialLoading, phases = none)

    assertNull(defaultSelection(loading, finished))
  }

  @Test
  fun `a live section that failed or kept a read error chooses nothing`() {
    val failed = WorkUiState(needsYou = none, goals = PagedListState.InitialError(problem), phases = none)
    val stale = WorkUiState(needsYou = none, goals = PagedListState.Content(listOf(workItem("g1")), readError = problem), phases = none)

    assertNull(defaultSelection(failed, finished))
    assertNull(defaultSelection(stale, finished))
  }

  @Test
  fun `the first live item in section order is chosen`() {
    val work = WorkUiState(needsYou = loaded(workItem("n1")), goals = loaded(workItem("g1")), phases = none)

    assertEquals(workItem("n1").identity, defaultSelection(work, finished))
  }

  @Test
  fun `with no live work the first finished item is chosen`() {
    val work = WorkUiState(needsYou = none, goals = none, phases = none)

    assertEquals(workItem("done-1").identity, defaultSelection(work, finished))
  }

  @Test
  fun `more live work still to be paged in holds back a finished item`() {
    val work = WorkUiState(needsYou = none, goals = loadedList(emptyList(), hasMore = true), phases = none)

    assertNull(defaultSelection(work, finished))
  }

  @Test
  fun `a finished item is chosen only from a history list that was read cleanly`() {
    val work = WorkUiState(needsYou = none, goals = none, phases = none)

    assertNull(defaultSelection(work, HistoryUiState()))
    assertNull(defaultSelection(work, HistoryUiState(list = none)))
    assertNull(defaultSelection(work, HistoryUiState(list = PagedListState.InitialError(problem))))
  }
}
