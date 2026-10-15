package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the board sections hold for one owner. Each section is its own [PagedListState], so one section loading, failing or
 * appending never changes another's. [recent] completes the board that the arrangement, the tray and the transition
 * notifications are computed from; it is never listed on screen.
 */
data class TrackedSections(
  val needsYou: PagedListState<WorkItem> = PagedListState.InitialLoading,
  val goals: PagedListState<WorkItem> = PagedListState.InitialLoading,
  val phases: PagedListState<WorkItem> = PagedListState.InitialLoading,
  val recent: PagedListState<WorkItem> = PagedListState.InitialLoading,
  val store: StoreObservation? = null,
  /** The observation every section was last read from; null while they were read from different stores or sessions. */
  val observation: StoreObservation? = null,
) {
  private val liveLists = listOf(needsYou, goals, phases)

  /** Live work in section order, each item once. */
  val liveItems: List<WorkItem> = liveLists.flatMap { it.items }.distinctBy { it.identity }

  /** Live and recent work: the board the arrangement, the tray and the notifications see. */
  val trackedItems: List<WorkItem> = (liveItems + recent.items).distinctBy { it.identity }

  /** Whether any live section has been read, so there is a board to show. */
  val loaded: Boolean = liveLists.any { it.isRead() }

  /** Whether every section the board is built from has been read once. */
  val settled: Boolean = (liveLists + recent).all { it.isRead() }

  /** The first read problem of a live section, if there is one. */
  val liveProblem: Problem? = liveLists.firstNotNullOfOrNull { it.problem() }

  /** The board is the last successful read, because a live section could not be refreshed. */
  val stale: Boolean = liveProblem != null && loaded

  /** Whether more live work can be requested. */
  val hasMoreLive: Boolean = liveLists.any { it is PagedListState.Content && it.append != AppendState.Exhausted }
}

private fun PagedListState<WorkItem>.isRead(): Boolean = this is PagedListState.Content || this is PagedListState.LoadedEmpty

private fun PagedListState<WorkItem>.problem(): Problem? = when (this) {
  is PagedListState.InitialError -> problem
  is PagedListState.Content -> readError
  else -> null
}

/** Live sections hold only what the runtime says is live; an item that finished while the page was read is dropped. */
private fun isLive(item: WorkItem): Boolean = item.liveSection != null

/**
 * The live board sections (needs you, goals, standalone phases) and, unless [includeRecent] is false, the recent section the
 * board is completed with. One owner builds one instance: it keeps its own pagers and its own board observations, so
 * owners never supersede each other's reads. It is fed the preferences through [apply]; everything runs on [scope]'s
 * dispatcher, which must be single-threaded.
 */
class TrackedBoardSections(
  private val scope: CoroutineScope,
  private val boards: WorkBoardRepository,
  includeRecent: Boolean = true,
) {
  private val needsYou = SectionPager(scope, boards, WorkSection.NEEDS_YOU, ::isLive)
  private val goals = SectionPager(scope, boards, WorkSection.GOALS, ::isLive)
  private val phases = SectionPager(scope, boards, WorkSection.PHASES, ::isLive)
  private val recent = SectionPager(scope, boards, WorkSection.RECENT)
  private val live = listOf(needsYou, goals, phases)
  private val pagers = if (includeRecent) live + recent else live
  private val recentIncluded = includeRecent

  private var filter = ""

  /** Preferences the sections were last started with; null until the first read of them, and again after one fails. */
  private var applied: AppSettings? = null

  private val stores = combine(pagers.map(SectionPager::store)) { it.toList() }

  val state: StateFlow<TrackedSections> =
    combine(needsYou.state, goals.state, phases.state, recent.state, stores) { n, g, p, r, s ->
      TrackedSections(n, g, p, r, store = s.firstNotNullOfOrNull { it }, observation = s.distinct().singleOrNull())
    }.stateIn(scope, SharingStarted.Eagerly, TrackedSections())

  init {
    scope.launch {
      boards.refreshEvents.collect { changed -> pagers.filter { it.section in changed }.forEach { it.markRefreshing() } }
    }
  }

  /** Starts, restarts or fails the sections for the latest preferences. */
  fun apply(result: SettingsState) {
    when (result) {
      SettingsState.Loading -> Unit
      is SettingsState.Failed -> {
        applied = null
        pagers.forEach { it.failInitial(result.problem) }
      }
      is SettingsState.Ready -> {
        val next = result.settings
        val previous = applied
        applied = next
        // A different store or an unread one shows nothing until it answers; a different retention keeps the rows meanwhile.
        if (previous == null || previous.databasePath != next.databasePath) startAll(next.retention, clear = true)
        else if (previous.retention != next.retention) startAll(next.retention, clear = false)
      }
    }
  }

  /** Narrows the live sections by [text]; the recent section is never filtered. */
  fun changeFilter(text: String) {
    if (filter == text) return
    filter = text
    applied?.let { current -> live.forEach { it.start(SectionQuery(current.retention, text)) } }
  }

  fun retry(section: WorkSection) {
    pagers.firstOrNull { it.section == section }?.retry()
  }

  fun loadMore(section: WorkSection) {
    pagers.firstOrNull { it.section == section }?.loadMore()
  }

  fun loadMoreLive() {
    live.forEach(SectionPager::loadMore)
  }

  /** Retries sections whose first read failed, marks the others as refreshing and asks the board to reconnect. */
  fun refresh() {
    pagers.forEach { pager ->
      if (pager.state.value is PagedListState.InitialError) pager.retry() else pager.markRefreshing()
    }
    scope.launch { boards.refresh() }
  }

  private fun startAll(retention: Retention, clear: Boolean) {
    live.forEach { it.start(SectionQuery(retention, filter), clear) }
    if (recentIncluded) recent.start(SectionQuery(retention), clear)
  }
}
