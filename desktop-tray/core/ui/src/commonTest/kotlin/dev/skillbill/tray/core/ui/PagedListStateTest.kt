package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import kotlin.test.Test
import kotlin.test.assertEquals

class PagedListStateTest {
  private val problem = Problem(ProblemCode.DATABASE_ACCESS, "Timed out")

  @Test
  fun `a completed read is empty only when no page follows`() {
    assertEquals(PagedListState.LoadedEmpty(), loadedList(emptyList<Int>(), hasMore = false))
    assertEquals(PagedListState.Content(emptyList<Int>(), append = AppendState.Idle), loadedList(emptyList<Int>(), hasMore = true))
    assertEquals(PagedListState.Content(listOf(1), append = AppendState.Exhausted), loadedList(listOf(1), hasMore = false))
    assertEquals(PagedListState.Content(listOf(1), append = AppendState.Idle), loadedList(listOf(1), hasMore = true))
  }

  @Test
  fun `a refresh keeps loaded rows and sends a failed first read back to loading`() {
    val content = PagedListState.Content(listOf(1, 2))

    assertEquals(content.copy(refreshing = true), content.refreshing())
    assertEquals(PagedListState.LoadedEmpty(refreshing = true), PagedListState.LoadedEmpty().refreshing())
    assertEquals(PagedListState.InitialLoading, PagedListState.InitialError(problem).refreshing())
    assertEquals(PagedListState.InitialLoading, PagedListState.InitialLoading.refreshing())
  }

  @Test
  fun `a failed read keeps rows already shown and is the only thing shown otherwise`() {
    val content = PagedListState.Content(listOf(1), refreshing = true)

    assertEquals(PagedListState.Content(listOf(1), refreshing = false, readError = problem), content.readFailed(problem))
    assertEquals(PagedListState.InitialError(problem), PagedListState.InitialLoading.readFailed(problem))
    assertEquals(PagedListState.InitialError(problem), PagedListState.LoadedEmpty().readFailed(problem))
  }

  @Test
  fun `a failed append never touches the read state and applies only to rows`() {
    val content = PagedListState.Content(listOf(1), append = AppendState.Loading)

    assertEquals(PagedListState.Content(listOf(1), append = AppendState.Failed(problem)), content.appendFailed(problem))
    assertEquals(PagedListState.LoadedEmpty(), PagedListState.LoadedEmpty().appendFailed(problem))
  }

  @Test
  fun `abandoning an append returns the footer to idle`() {
    assertEquals(
      PagedListState.Content(listOf(1), append = AppendState.Idle),
      PagedListState.Content(listOf(1), append = AppendState.Loading).appendCancelled(),
    )
    val failed = PagedListState.Content(listOf(1), append = AppendState.Failed(problem))
    assertEquals(failed, failed.appendCancelled())
  }

  @Test
  fun `items are empty for every state without rows`() {
    assertEquals(emptyList(), PagedListState.InitialLoading.items)
    assertEquals(emptyList(), PagedListState.InitialError(problem).items)
    assertEquals(emptyList(), PagedListState.LoadedEmpty().items)
    assertEquals(listOf(1, 2), PagedListState.Content(listOf(1, 2)).items)
  }
}
