package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.BoardPage
import dev.skillbill.desktop.core.data.runtime.BoardReads
import dev.skillbill.desktop.core.data.signal.SignalSnapshot
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardSection
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.LoadMoreOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import skillbill.contracts.persistence.DesktopReadSchema
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private const val SHARE_TIMEOUT_MS = 5_000L

/**
 * One session's board observations: one [StateFlow] per distinct [BoardQuery], so the window, the tray and notifications
 * share one read per change. A query re-reads when the `goals` or `phases` revision moves, on a resynchronizing snapshot, at
 * the earliest deadline the runtime returned, and on [refresh]. `activity` and `specs` never re-read the board.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardObservations internal constructor(
  private val scope: CoroutineScope,
  private val signals: StateFlow<SignalSnapshot>,
  private val reads: BoardReads,
  private val clock: Clock,
) {
  private val observations = HashMap<BoardQuery, Observation>()
  private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /** Counts collectors per query; the last one leaving evicts the query once its share timeout passes unclaimed. */
  fun observe(query: BoardQuery): Flow<BoardState> = flow {
    val observation = synchronized(observations) {
      observations.getOrPut(query) { Observation(query) }.also { it.subscribers++ }
    }
    try {
      emitAll(observation.state)
    } finally {
      synchronized(observations) { observation.subscribers-- }
      scope.launch {
        delay(SHARE_TIMEOUT_MS)
        synchronized(observations) { if (observation.subscribers == 0) observations.remove(query, observation) }
      }
    }
  }

  internal fun observeComplete(query: BoardQuery): Flow<Outcome<BoardPage, ReadProblem>> = flow {
    val deadline = MutableStateFlow<Instant?>(null)
    val deadlineTicks = deadline.flatMapLatest { at ->
      if (at == null) emptyFlow() else flow {
        delay(at - clock.now() + 1.milliseconds)
        emit(Unit)
      }
    }
    emitAll(
      merge(
        signals.map {
          RevisionKey(it.generation, it.revisions[DesktopReadSchema.GOALS_TOPIC], it.revisions[DesktopReadSchema.PHASES_TOPIC], it.resyncEpoch)
        }.distinctUntilChanged().map { },
        deadlineTicks,
        refreshes,
      ).conflate().mapLatest { reads.complete(query) }
        .onEach { result -> if (result is Outcome.Success) deadline.value = nextDeadline(result.value) }
        .distinctUntilChanged(),
    )
  }

  internal suspend fun completeSnapshot(query: BoardQuery): Outcome<BoardPage, ReadProblem> = reads.complete(query)

  suspend fun loadMore(query: BoardQuery, cursor: String): LoadMoreOutcome {
    val observation = synchronized(observations) { observations[query] } ?: return LoadMoreOutcome.End
    return when (val read = reads.page(query, cursor)) {
      is Outcome.Failure -> {
        refreshes.tryEmit(Unit)
        LoadMoreOutcome.Failed(read.error)
      }
      is Outcome.Success -> {
        observation.pageCount.incrementAndGet()
        observation.appends.emit(read.value)
        if (read.value.items.isEmpty()) LoadMoreOutcome.End else LoadMoreOutcome.Appended
      }
    }
  }

  fun refresh() {
    refreshes.tryEmit(Unit)
  }

  private sealed interface Event {
    data class Read(val result: Outcome<BoardPage, ReadProblem>) : Event

    data class Append(val page: BoardPage) : Event
  }

  private data class RevisionKey(val generation: String?, val goals: Long?, val phases: Long?, val resyncEpoch: Long)

  private inner class Observation(private val query: BoardQuery) {
    var subscribers = 0
    val pageCount = AtomicInteger(1)
    val appends = MutableSharedFlow<BoardPage>(extraBufferCapacity = 8)

    /** The single scheduled re-query; each result replaces it, so no repeating timer exists. */
    private val deadline = MutableStateFlow<Instant?>(null)

    private val deadlineTicks: Flow<Unit> = deadline.flatMapLatest { at ->
      if (at == null) emptyFlow() else flow {
        delay(at - clock.now() + 1.milliseconds)
        emit(Unit)
      }
    }

    private val triggers: Flow<Unit> = merge(
      signals.map {
        RevisionKey(it.generation, it.revisions[DesktopReadSchema.GOALS_TOPIC], it.revisions[DesktopReadSchema.PHASES_TOPIC], it.resyncEpoch)
      }.distinctUntilChanged().map { },
      deadlineTicks,
      refreshes,
    )

    val state: StateFlow<BoardState> = merge(
      triggers.conflate().mapLatest {
        Event.Read(if (query.complete) reads.complete(query) else reads.pages(query, pageCount.get()))
      },
      appends.map { Event.Append(it) },
    ).onEach { event ->
      if (event is Event.Read && event.result is Outcome.Success) deadline.value = nextDeadline(event.result.value)
    }.runningFold<Event, BoardState>(BoardState.Loading, ::fold)
      .distinctUntilChanged()
      .stateIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), BoardState.Loading)

    private fun fold(previous: BoardState, event: Event): BoardState = when (event) {
      is Event.Read -> when (val result = event.result) {
        is Outcome.Success -> loaded(result.value.items, result.value.nextCursor)
        is Outcome.Failure -> if (previous is BoardState.Loaded) previous.copy(stale = true) else BoardState.Failed(result.error)
      }
      is Event.Append ->
        if (previous is BoardState.Loaded) {
          // A re-read that started after loadMore counted the page may already hold these rows; append each id once.
          val shown = previous.items.mapTo(HashSet()) { it.id }
          loaded(previous.items + event.page.items.filterNot { it.id in shown }, event.page.nextCursor)
        } else {
          previous
        }
    }

    private fun loaded(items: List<WorkItem>, nextCursor: String?) =
      BoardState.Loaded(sectionsOf(items), reads.counts(items), nextCursor, stale = false, items = items)
  }
}

/**
 * The earliest membership or freshness deadline the runtime returned that is after its own observation time. A deadline at
 * or before it is skipped, so a lagging runtime clock cannot cause a re-query loop. This schedules; it judges no membership.
 */
private fun nextDeadline(page: BoardPage): Instant? =
  page.items.flatMap { listOfNotNull(it.membershipDeadline, it.freshnessDeadline) }.filter { it > page.observedAt }.minOrNull()

/** Partitions [items] by the runtime's live section verdict, keeping runtime order; finished work goes under Finished. */
private fun sectionsOf(items: List<WorkItem>): List<BoardSection> {
  val byKind = items.groupBy { item ->
    when (item.liveSection) {
      WorkLiveSection.NeedsYou -> BoardSectionKind.NeedsYou
      WorkLiveSection.Goals -> BoardSectionKind.Goals
      WorkLiveSection.Phases -> BoardSectionKind.Phases
      WorkLiveSection.Operations -> BoardSectionKind.Operations
      null -> if (item.finishedAt != null) BoardSectionKind.Finished else null
    }
  }
  return BoardSectionKind.entries.map { BoardSection(it, byKind[it].orEmpty()) }
}
