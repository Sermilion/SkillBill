package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeWorkBoardRepository
import dev.skillbill.tray.core.testing.observedBoard
import dev.skillbill.tray.core.testing.workItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SectionPagerTest {
  private val boards = FakeWorkBoardRepository()
  private val section = WorkSection.GOALS
  private val query = SectionQuery(Retention.DAY)

  private fun item(id: String): WorkItem = workItem(id)

  private fun TestScope.pager(): SectionPager = SectionPager(backgroundScope, boards, section).also {
    it.start(query)
    runCurrent()
  }

  private fun ids(state: PagedListState<WorkItem>): List<String> = state.items.map { it.identity.id }

  @Test
  fun `a section is loading until the store answers and then shows what it read`() = runTest {
    val pager = pager()
    assertEquals(PagedListState.InitialLoading, pager.state.value)

    boards.emit(section, Outcome.Value(observedBoard(listOf(item("a"), item("b")))))
    runCurrent()

    assertEquals(listOf("a", "b"), ids(pager.state.value))
    assertEquals(StoreObservation(generation = 1, databasePath = null, observationSession = 1), pager.store.value)
  }

  @Test
  fun `a section with no rows and no next page is loaded empty`() = runTest {
    val pager = pager()

    boards.emit(section, Outcome.Value(observedBoard(emptyList())))
    runCurrent()

    assertEquals(PagedListState.LoadedEmpty(), pager.state.value)
  }

  @Test
  fun `a failed first read is an error and a failed re-read keeps the rows`() = runTest {
    val pager = pager()
    val problem = Problem(ProblemCode.DATABASE_ACCESS, "Unreadable")

    boards.emit(section, Outcome.Failure(problem))
    runCurrent()
    assertEquals(PagedListState.InitialError(problem), pager.state.value)

    pager.retry()
    runCurrent()
    boards.emit(section, Outcome.Value(observedBoard(listOf(item("a")))))
    runCurrent()
    boards.emit(section, Outcome.Failure(problem))
    runCurrent()

    val content = assertIs<PagedListState.Content<WorkItem>>(pager.state.value)
    assertEquals(listOf("a"), ids(content))
    assertEquals(problem, content.readError)
  }

  @Test
  fun `a store change keeps rows visible as refreshing without clearing them`() = runTest {
    val pager = pager()
    boards.emit(section, Outcome.Value(observedBoard(listOf(item("a")))))
    runCurrent()

    pager.markRefreshing()

    val content = assertIs<PagedListState.Content<WorkItem>>(pager.state.value)
    assertTrue(content.refreshing)
    assertEquals(listOf("a"), ids(content))
  }

  @Test
  fun `a page that answers another query is refused as stale and asks for a re-read`() = runTest {
    val pager = pager()

    boards.emit(section, Outcome.Value(observedBoard(listOf(item("a")), filter = "other")))
    runCurrent()

    val error = assertIs<PagedListState.InitialError>(pager.state.value)
    assertEquals(ProblemCode.STALE_TARGET, error.problem.code)
    assertEquals(1, boards.refreshCount)
  }

  @Test
  fun `loading more appends the next page once and ends exhausted`() = runTest {
    val pager = pager()
    boards.onBoard = { Outcome.Value(observedBoard(listOf(item("b")))) }
    boards.emit(section, Outcome.Value(observedBoard(listOf(item("a")), nextCursor = "next")))
    runCurrent()

    pager.loadMore()
    pager.loadMore()
    runCurrent()

    assertEquals(1, boards.readCalls.size)
    assertEquals("next", boards.readCalls.single().cursor)
    assertEquals(listOf("a", "b"), ids(pager.state.value))
    assertEquals(AppendState.Exhausted, assertIs<PagedListState.Content<WorkItem>>(pager.state.value).append)
  }

  @Test
  fun `a failed append keeps the rows and can be retried`() = runTest {
    val pager = pager()
    val problem = Problem(ProblemCode.DATABASE_ACCESS, "Timed out")
    boards.onBoard = { Outcome.Failure(problem) }
    boards.emit(section, Outcome.Value(observedBoard(listOf(item("a")), nextCursor = "next")))
    runCurrent()

    pager.loadMore()
    runCurrent()

    val failed = assertIs<PagedListState.Content<WorkItem>>(pager.state.value)
    assertEquals(listOf("a"), ids(failed))
    assertEquals(AppendState.Failed(problem), failed.append)

    boards.onBoard = { Outcome.Value(observedBoard(listOf(item("b")))) }
    pager.loadMore()
    runCurrent()

    assertEquals(listOf("a", "b"), ids(pager.state.value))
  }

  @Test
  fun `restarting while an append is in flight discards the old answer`() = runTest {
    val pager = pager()
    val gate = CompletableDeferred<Outcome<Board>>()
    boards.onBoard = { gate.await() }
    boards.emit(section, Outcome.Value(observedBoard(listOf(item("a")), nextCursor = "old-next")))
    runCurrent()
    pager.loadMore()
    runCurrent()
    assertEquals(AppendState.Loading, assertIs<PagedListState.Content<WorkItem>>(pager.state.value).append)

    pager.start(SectionQuery(Retention.DAY, filter = "x"))
    runCurrent()
    gate.complete(Outcome.Value(observedBoard(listOf(item("late")), filter = "")))
    runCurrent()

    // The abandoned append neither lands nor leaves the footer loading.
    val content = assertIs<PagedListState.Content<WorkItem>>(pager.state.value)
    assertEquals(listOf("a"), ids(content))
    assertTrue(content.refreshing)
    assertEquals(AppendState.Idle, content.append)
  }

  @Test
  fun `a changed history filter or query clears rows and cannot append the old cursor`() = runTest {
    val history = SectionPager(backgroundScope, boards, WorkSection.HISTORY)
    history.start(SectionQuery(Retention.DAY, historyFilter = HistoryFilter.ALL))
    runCurrent()
    boards.emit(WorkSection.HISTORY, Outcome.Value(observedBoard(listOf(item("all")), nextCursor = "all-next")))
    runCurrent()

    history.start(SectionQuery(Retention.DAY, historyFilter = HistoryFilter.GOALS), clear = true)
    runCurrent()
    assertEquals(PagedListState.InitialLoading, history.state.value)
    history.loadMore()
    runCurrent()
    assertTrue(boards.readCalls.isEmpty(), "No page may be requested with the previous filter's cursor.")

    boards.emit(
      WorkSection.HISTORY,
      Outcome.Value(observedBoard(listOf(item("goal")), nextCursor = "goals-next", historyFilter = HistoryFilter.GOALS)),
    )
    runCurrent()
    boards.onBoard = { Outcome.Value(observedBoard(listOf(item("older")), historyFilter = HistoryFilter.GOALS)) }
    history.loadMore()
    runCurrent()

    assertEquals(listOf("goals-next"), boards.readCalls.map { it.cursor })
    assertEquals(HistoryFilter.GOALS, boards.readCalls.single().historyFilter)
    assertEquals(listOf("goal", "older"), ids(history.state.value))
  }

  @Test
  fun `the window keeps five pages and re-anchors the observation after the dropped one`() = runTest {
    val pager = pager()
    boards.onBoard = { call ->
      val page = call.cursor!!.removePrefix("c").toInt()
      Outcome.Value(observedBoard(listOf(item("p$page")), nextCursor = "c${page + 1}"))
    }
    boards.emit(section, Outcome.Value(observedBoard(listOf(item("p0")), nextCursor = "c1")))
    runCurrent()

    repeat(5) {
      pager.loadMore()
      runCurrent()
    }

    assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), ids(pager.state.value))
    assertEquals("c1", boards.observeCalls.last().anchor)
    assertEquals(MAX_SECTION_PAGES, boards.retained.last().pageKeys.size)
  }
}
