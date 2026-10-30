package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.data.runtime.DetailRead
import dev.skillbill.desktop.core.data.runtime.DetailReads
import dev.skillbill.desktop.core.data.runtime.size
import dev.skillbill.desktop.core.data.runtime.withList
import dev.skillbill.desktop.core.data.signal.ChangedIds
import dev.skillbill.desktop.core.data.signal.SignalSnapshot
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import skillbill.contracts.persistence.DesktopReadSchema
import java.util.concurrent.ConcurrentHashMap

private const val PREFETCH_CAPACITY = 3

private val DETAIL_TOPICS = listOf(DesktopReadSchema.GOALS_TOPIC, DesktopReadSchema.PHASES_TOPIC, DesktopReadSchema.ACTIVITY_TOPIC)

/**
 * One session's detail observations and prefetch cache. A detail loads once, then refreshes only when a `goals`, `phases`
 * or `activity` change may touch it, replacing its sections in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailObservations internal constructor(
  private val scope: CoroutineScope,
  private val signals: StateFlow<SignalSnapshot>,
  private val reads: DetailReads,
) {
  /** A first load and the signal snapshot captured before it ran, so a commit landing during the read still triggers. */
  private class Loaded(val read: DetailRead, val applied: SignalSnapshot)

  private val lock = Any()
  private val cache = object : LinkedHashMap<String, Loaded>(PREFETCH_CAPACITY + 1, 1f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Loaded>) = size > PREFETCH_CAPACITY
  }
  private var inFlight: Pair<String, Deferred<Loaded>>? = null

  /** Every open observer of an id, for [loadList], and how many rows of each list the user has paged in for that id. */
  private val open = ConcurrentHashMap<String, Set<MutableStateFlow<DetailState>>>()
  private val extended = ConcurrentHashMap<String, Map<DetailList, Int>>()

  /** The header from the row is emitted before any read; the collector has it before the first load starts. */
  fun observe(item: WorkItem): Flow<DetailState> = flow {
    emit(DetailState(item, DetailSections.Pending))
    val first = synchronized(lock) { cache[item.id] } ?: inFlightFor(item.id)?.awaitOrNull() ?: load(item.id)
    val live = MutableStateFlow(first.read.toState(item))
    open.compute(item.id) { _, observers -> observers.orEmpty() + live }
    try {
      coroutineScope {
        launch { refreshes(item, first.applied).collect { read -> live.update { previous -> previous.refreshedBy(read) } } }
        emitAll(live)
      }
    } finally {
      // The last observer of the id clears its paged sizes; another still open keeps the rows it paged in.
      open.compute(item.id) { id, observers ->
        (observers.orEmpty() - live).ifEmpty { null }.also { if (it == null) extended.remove(id) }
      }
    }
  }

  /** Re-reads per [needsRefresh]; activity churn only reaches here, so it never restarts the first load. */
  private fun refreshes(item: WorkItem, appliedAtLoad: SignalSnapshot): Flow<DetailRead> {
    val ids = setOfNotNull(item.id, item.control.workflowId, item.control.invocationId)
    var applied = appliedAtLoad
    return signals
      .mapNotNull { snapshot -> refreshKind(snapshot, applied, ids).also { applied = snapshot } }
      .conflate()
      .mapLatest { kind ->
        if (kind == DetailRefreshKind.ACTIVITY) reads.activity(item.id) else reads.refresh(item.id, extended[item.id].orEmpty())
      }
  }

  suspend fun loadList(id: String, list: DetailList, cursor: String?): DetailListOutcome {
    val observers = open[id] ?: return DetailListOutcome.End
    return when (val read = reads.list(id, list, cursor)) {
      is DetailRead.Failed -> DetailListOutcome.Failed(read.problem)
      DetailRead.Gone -> DetailListOutcome.End
      is DetailRead.Activity -> DetailListOutcome.End
      is DetailRead.Loaded -> {
        val next = read.sections.listCursors[list]
        val updated = observers.mapNotNull { live ->
          live.updateAndGetSections { it.withList(list, read.sections.detail, next, append = true) }
        }
        updated.firstOrNull()?.let { sections -> extended.merge(id, mapOf(list to sections.detail.size(list))) { old, new -> old + new } }
        if (read.sections.detail.size(list) == 0) DetailListOutcome.End else DetailListOutcome.Appended
      }
    }
  }

  /** Runs the first load for [id] in the session scope, replacing an in-flight prefetch of another id. */
  suspend fun prefetch(id: String): RunOptions? {
    val cached = synchronized(lock) { cache[id] }
    if (cached != null && !needsRefresh(signals.value, cached.applied, cached.read.detailIds(id))) return cached.read.runOptions()
    val pending = synchronized(lock) {
      inFlight?.takeIf { it.first == id }?.second ?: run {
        inFlight?.second?.cancel()
        scope.async(start = CoroutineStart.LAZY) {
          load(id).also { loaded ->
            synchronized(lock) {
              // A failed or gone read is not reused, so opening the row reads again instead of showing a stale failure.
              if (loaded.read is DetailRead.Loaded) cache[id] = loaded
              if (inFlight?.first == id) inFlight = null
            }
          }
        }.also { inFlight = id to it }
      }
    }
    pending.start()
    return pending.awaitOrNull()?.read?.runOptions()
  }

  private fun inFlightFor(id: String): Deferred<Loaded>? = synchronized(lock) { inFlight?.takeIf { it.first == id }?.second }

  private suspend fun load(id: String): Loaded {
    val applied = signals.value
    return Loaded(reads.first(id), applied)
  }

  /** Null when another prefetch cancelled this one; the caller's own cancellation still propagates. */
  private suspend fun Deferred<Loaded>.awaitOrNull(): Loaded? = try {
    await()
  } catch (failure: CancellationException) {
    currentCoroutineContext().ensureActive()
    null
  }
}

/**
 * Subtask 1's change test, per `goals`, `phases` and `activity` topic that moved past [applied]: a resynchronize, an
 * unlisted or overflowed topic, a base above the applied revision, or an id of this item listed means a refresh.
 */
private enum class DetailRefreshKind { ACTIVITY, FULL }

private fun refreshKind(snapshot: SignalSnapshot, applied: SignalSnapshot, ids: Set<String>): DetailRefreshKind? {
  if (snapshot.resyncEpoch != applied.resyncEpoch || snapshot.generation != applied.generation) return DetailRefreshKind.FULL
  var activityChanged = false
  for (topic in DETAIL_TOPICS) {
    val revision = snapshot.revisions[topic] ?: continue
    val last = applied.revisions[topic] ?: return DetailRefreshKind.FULL
    if (revision <= last) continue
    val changedThisItem = when (val changed = snapshot.changed[topic] ?: ChangedIds.All) {
      ChangedIds.All -> true
      is ChangedIds.Ids -> changed.base > last || changed.ids.any { it in ids }
    }
    if (changedThisItem) {
      if (topic == DesktopReadSchema.ACTIVITY_TOPIC) activityChanged = true else return DetailRefreshKind.FULL
    }
  }
  return if (activityChanged) DetailRefreshKind.ACTIVITY else null
}

private fun needsRefresh(snapshot: SignalSnapshot, applied: SignalSnapshot, ids: Set<String>): Boolean =
  refreshKind(snapshot, applied, ids) != null

private fun DetailRead.runOptions(): RunOptions? = (this as? DetailRead.Loaded)?.sections?.runOptions

private fun DetailRead.toState(row: WorkItem): DetailState = when (this) {
  is DetailRead.Loaded -> DetailState(header.withBoardVerdicts(row), sections)
  is DetailRead.Activity -> DetailState(header.withBoardVerdicts(row), DetailSections.Pending)
  DetailRead.Gone -> DetailState(row, DetailSections.Gone)
  is DetailRead.Failed -> DetailState(row, DetailSections.Failed(problem))
}

/** A refresh replaces the sections; a failed one keeps the loaded sections marked stale. */
private fun DetailState.refreshedBy(read: DetailRead): DetailState = when (read) {
  is DetailRead.Loaded -> DetailState(read.header.withBoardVerdicts(header), read.sections)
  is DetailRead.Activity -> when (val current = sections) {
    is DetailSections.Loaded -> copy(
      header = read.header.withBoardVerdicts(header),
      sections = current.copy(detail = current.detail.copy(latestActivity = read.latestActivity)),
    )
    else -> copy(header = read.header.withBoardVerdicts(header))
  }
  DetailRead.Gone -> copy(sections = DetailSections.Gone)
  is DetailRead.Failed -> when (val current = sections) {
    is DetailSections.Loaded -> copy(sections = current.copy(stale = true))
    else -> copy(sections = DetailSections.Failed(read.problem))
  }
}

/** The detail read carries no running or membership verdicts, so the loaded header keeps the ones the board row had. */
private fun WorkItem.withBoardVerdicts(row: WorkItem): WorkItem = copy(
  running = row.running,
  recentlyFinishedPhase = row.recentlyFinishedPhase,
  doneWithinDay = row.doneWithinDay,
  membershipDeadline = row.membershipDeadline,
)

private fun DetailRead.detailIds(id: String): Set<String> = when (this) {
  is DetailRead.Loaded -> setOfNotNull(id, header.control.workflowId, header.control.invocationId)
  is DetailRead.Activity -> setOfNotNull(id, header.control.workflowId, header.control.invocationId)
  DetailRead.Gone, is DetailRead.Failed -> setOf(id)
}

private fun MutableStateFlow<DetailState>.updateAndGetSections(
  transform: (DetailSections.Loaded) -> DetailSections.Loaded,
): DetailSections.Loaded? {
  var result: DetailSections.Loaded? = null
  update { state ->
    val sections = state.sections as? DetailSections.Loaded ?: return@update state
    state.copy(sections = transform(sections).also { result = it })
  }
  return result
}
