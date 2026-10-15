package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.Problem

/** Where the next page of a list stands. [Idle] means another page exists and nothing is fetching it. */
sealed interface AppendState {
  data object Idle : AppendState
  data object Loading : AppendState
  data class Failed(val problem: Problem) : AppendState
  data object Exhausted : AppendState
}

/**
 * One list's lifecycle as a screen renders it. Every state is distinct, so a screen never infers "empty" from "not loaded
 * yet" and a failed read never wipes rows that were already shown.
 */
sealed interface PagedListState<out T> {
  /** Nothing has been read for the current query yet. */
  data object InitialLoading : PagedListState<Nothing>

  /** The first read failed, so there is nothing to keep showing. */
  data class InitialError(val problem: Problem) : PagedListState<Nothing>

  /** The read finished and the query has no rows. */
  data class LoadedEmpty(val refreshing: Boolean = false) : PagedListState<Nothing>

  /** Rows are shown; [readError] is a failed re-read that kept them, [append] is the next page's state. */
  data class Content<T>(
    val items: List<T>,
    val refreshing: Boolean = false,
    val readError: Problem? = null,
    val append: AppendState = AppendState.Idle,
  ) : PagedListState<T>
}

val <T> PagedListState<T>.items: List<T>
  get() = (this as? PagedListState.Content<T>)?.items.orEmpty()

/** A re-read has started: loaded rows stay visible, and a failed first read goes back to loading. */
fun <T> PagedListState<T>.refreshing(): PagedListState<T> = when (this) {
  PagedListState.InitialLoading, is PagedListState.InitialError -> PagedListState.InitialLoading
  is PagedListState.LoadedEmpty -> copy(refreshing = true)
  is PagedListState.Content -> copy(refreshing = true)
}

/** A read failed: rows already shown are kept with the error, otherwise the failure is all there is to show. */
fun <T> PagedListState<T>.readFailed(problem: Problem): PagedListState<T> = when (this) {
  is PagedListState.Content -> copy(refreshing = false, readError = problem)
  else -> PagedListState.InitialError(problem)
}

/** A page append failed: the rows stay and the footer offers a retry. */
fun <T> PagedListState<T>.appendFailed(problem: Problem): PagedListState<T> = when (this) {
  is PagedListState.Content -> copy(append = AppendState.Failed(problem))
  else -> this
}

/** An append in flight was abandoned with its read, so the footer must not keep showing it as loading. */
fun <T> PagedListState<T>.appendCancelled(): PagedListState<T> = when {
  this is PagedListState.Content && append == AppendState.Loading -> copy(append = AppendState.Idle)
  else -> this
}

/** The state for a completed read of [items], where [hasMore] says another page can be requested. */
fun <T> loadedList(items: List<T>, hasMore: Boolean): PagedListState<T> = when {
  items.isEmpty() && !hasMore -> PagedListState.LoadedEmpty()
  else -> PagedListState.Content(items, append = if (hasMore) AppendState.Idle else AppendState.Exhausted)
}
