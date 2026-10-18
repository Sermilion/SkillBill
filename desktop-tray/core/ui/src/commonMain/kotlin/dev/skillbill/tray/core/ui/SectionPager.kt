package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.BoardRetention
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one section is asked for. A new query restarts the section; the same query only re-reads. */
data class SectionQuery(
  val retention: Retention,
  val filter: String = "",
  val historyFilter: HistoryFilter = HistoryFilter.ALL,
)

/** Pages kept in memory per section; older pages drop out and the observation re-anchors after them. */
const val MAX_SECTION_PAGES = 5

/**
 * The store a section was last read from: [generation] changes when the store was replaced, so what was selected no longer exists.
 * [observationSession] changes when the same store was reopened through a new connection.
 */
data class StoreObservation(val generation: Long, val databasePath: String?, val observationSession: Long = 0)

/**
 * One board section's paged state, owned by exactly one ViewModel. It has its own generation counter, cursor window and
 * append job, so a section that is loading, failing or appending never disturbs another section's state.
 *
 * Everything runs on [scope]'s dispatcher; the mutex only orders the suspension points inside one read. A result is
 * applied only if the generation it started under is still current, so an answer to a replaced query is discarded.
 */
class SectionPager(
  private val scope: CoroutineScope,
  private val boards: WorkBoardRepository,
  val section: WorkSection,
  private val accepts: (WorkItem) -> Boolean,
) {
  private val mutable = MutableStateFlow<PagedListState<WorkItem>>(PagedListState.InitialLoading)
  val state: StateFlow<PagedListState<WorkItem>> = mutable.asStateFlow()

  private val observedStore = MutableStateFlow<StoreObservation?>(null)
  val store: StateFlow<StoreObservation?> = observedStore.asStateFlow()

  private val mutex = Mutex()
  private var generation = 0L
  private var query: SectionQuery? = null
  private var observeJob: Job? = null
  private var appendJob: Job? = null
  private var window: List<Board> = emptyList()
  private var pages = 1
  private var anchor: String? = null
  private var lastStoreGeneration: Long? = null

  /**
   * Starts reading [query]. With [clear] the rows shown so far are dropped (the query changed what they mean); without it they
   * stay visible, marked as refreshing, until the new read lands.
   */
  fun start(query: SectionQuery, clear: Boolean = false) {
    generation++
    this.query = query
    observeJob?.cancel()
    appendJob?.cancel()
    appendJob = null
    window = emptyList()
    pages = 1
    anchor = null
    mutable.value = if (clear) PagedListState.InitialLoading else mutable.value.refreshing().appendCancelled()
    subscribe()
  }

  /** The list cannot be read at all right now (for example the preferences are unreadable). */
  fun failInitial(problem: Problem) {
    generation++
    observeJob?.cancel()
    appendJob?.cancel()
    appendJob = null
    mutable.value = PagedListState.InitialError(problem)
  }

  /** Re-reads the current query. */
  fun retry() {
    query?.let { start(it) }
  }

  /** The store announced a change: loaded rows stay and show that a re-read is under way. */
  fun markRefreshing() {
    mutable.update { current ->
      when (current) {
        is PagedListState.Content, is PagedListState.LoadedEmpty -> current.refreshing()
        else -> current
      }
    }
  }

  fun loadMore() {
    val current = mutable.value as? PagedListState.Content ?: return
    if (current.refreshing || current.append is AppendState.Loading || current.append is AppendState.Exhausted) return
    if (appendJob?.isActive == true) return
    val base = window.lastOrNull() ?: return
    val cursor = base.nextCursor ?: return
    val started = generation
    val active = query ?: return
    mutable.value = current.copy(append = AppendState.Loading)
    appendJob = scope.launch {
      mutex.withLock {
        if (started != generation) return@withLock
        val result = boards.board(section, active.retention, cursor, active.filter, active.historyFilter)
        if (started != generation) return@withLock
        when (result) {
          is Outcome.Failure -> mutable.update { it.appendFailed(result.problem) }
          is Outcome.Value -> if (sameObservation(base, result.value)) {
            var next = window + result.value
            var shifted = false
            if (next.size > MAX_SECTION_PAGES) {
              anchor = next.first().nextCursor
              next = next.drop(1)
              shifted = true
            }
            window = next
            pages = next.size
            publish(next)
            if (shifted) {
              observeJob?.cancel()
              subscribe()
            }
          } else {
            mutable.update { it.appendFailed(stalePage()) }
            boards.refresh()
          }
        }
      }
    }
  }

  private fun subscribe() {
    val started = generation
    val active = query ?: return
    observeJob = scope.launch {
      boards.observe(section, active.retention, active.filter, active.historyFilter, anchor).collect { outcome ->
        mutex.withLock { if (started == generation) apply(started, active, outcome) }
      }
    }
  }

  private suspend fun apply(started: Long, active: SectionQuery, outcome: Outcome<Board>) {
    when (outcome) {
      is Outcome.Failure -> {
        if (anchor != null && outcome.problem.code == ProblemCode.STALE_TARGET) {
          // The remembered anchor belongs to a database or connection that is gone: begin again from the first page.
          anchor = null
          window = emptyList()
          pages = 1
          observeJob?.cancel()
          subscribe()
        } else {
          mutable.update { it.readFailed(outcome.problem) }
        }
      }
      is Outcome.Value -> {
        val head = outcome.value
        if (!head.matches(active)) {
          mutable.update { it.readFailed(stalePage()) }
          boards.refresh()
          return
        }
        if (lastStoreGeneration != null && lastStoreGeneration != head.generation) pages = 1
        lastStoreGeneration = head.generation
        val read = mutableListOf(head)
        val seen = mutableSetOf<String>()
        repeat(pages - 1) {
          val cursor = read.last().nextCursor ?: return@repeat
          if (!seen.add(cursor)) {
            mutable.update { it.readFailed(stalePage()) }
            return
          }
          when (val next = boards.board(section, active.retention, cursor, active.filter, active.historyFilter)) {
            is Outcome.Failure -> {
              if (started == generation) mutable.update { it.readFailed(next.problem) }
              return
            }
            is Outcome.Value -> {
              if (!sameObservation(head, next.value)) {
                if (started == generation) mutable.update { it.readFailed(stalePage()) }
                boards.refresh()
                return
              }
              read += next.value
            }
          }
        }
        if (started != generation) return
        window = read
        observedStore.value = StoreObservation(head.generation, head.databasePath, head.observationSession)
        publish(read)
      }
    }
  }

  private fun publish(pagesRead: List<Board>) {
    retention(pagesRead)?.let(boards::retainPages)
    val items = pagesRead.flatMap { it.items }.filter(accepts).distinctBy { it.identity }
    mutable.value = loadedList(items, hasMore = pagesRead.last().nextCursor != null)
  }

  private fun retention(pagesRead: List<Board>): BoardRetention? {
    val first = pagesRead.firstOrNull() ?: return null
    if (pagesRead.any { !sameObservation(first, it) }) return null
    return BoardRetention(
      section = section,
      retention = first.finishedWithin,
      filter = first.filter,
      historyFilter = first.historyFilter,
      queryEpoch = first.queryEpoch,
      generation = first.generation,
      observationSession = first.observationSession,
      storeId = first.storeId,
      revision = first.revision,
      pageKeys = pagesRead.map(Board::pageKey),
    )
  }

  private fun stalePage() = Problem(ProblemCode.STALE_TARGET, "The page changed while this list was loading.")
}

private fun Board.matches(query: SectionQuery): Boolean =
  finishedWithin == query.retention && filter == query.filter && historyFilter == query.historyFilter &&
    queryEpoch > 0 && !storeId.isNullOrBlank() && revision != null

/** Pages are one coherent read only if they came from the same store revision, query and connection. */
private fun sameObservation(left: Board, right: Board): Boolean =
  left.generation == right.generation &&
    left.observationSession == right.observationSession &&
    left.storeId == right.storeId &&
    left.revision == right.revision &&
    left.queryEpoch == right.queryEpoch &&
    left.finishedWithin == right.finishedWithin &&
    left.filter == right.filter &&
    left.historyFilter == right.historyFilter
