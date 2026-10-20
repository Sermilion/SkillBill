package dev.skillbill.tray.core.data.repository

import androidx.paging.PagingSource
import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.paging.BoardPageFailure
import dev.skillbill.tray.core.data.paging.BoardPagingSource
import dev.skillbill.tray.core.data.port.CandidateRequest
import dev.skillbill.tray.core.data.port.CandidateRow
import dev.skillbill.tray.core.data.port.StoreObservation
import dev.skillbill.tray.core.data.session.ConcurrentRegistry
import dev.skillbill.tray.core.data.session.Counter
import dev.skillbill.tray.core.data.session.CursorCodec
import dev.skillbill.tray.core.data.session.DatabaseSession
import dev.skillbill.tray.core.data.session.DeadlineScheduler
import dev.skillbill.tray.core.data.session.MonotonicClock
import dev.skillbill.tray.core.data.session.OwnedRegistration
import dev.skillbill.tray.core.data.session.PageCursor
import dev.skillbill.tray.core.data.session.SessionGeneration
import dev.skillbill.tray.core.data.session.SessionParticipant
import dev.skillbill.tray.core.data.session.SessionToken
import dev.skillbill.tray.core.data.session.closeOwned
import dev.skillbill.tray.core.data.session.staleCursor
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.BoardRetention
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

internal const val PAGE_SIZE = 10
internal const val CANDIDATE_PAGE_SIZE = PAGE_SIZE + 1
internal const val CANDIDATE_BUDGET = 40

private const val READ_ATTEMPTS = 3

private class ReadPageAccounting(val section: WorkSection) {
  var attemptCount = 0
  var selectedRows = 0
  var projectionSubmitted = 0
  var projectionReturned = 0
  var selectionStatements = 0
  var selectionNanos = 0L
  var projectionNanos = 0L
  var accessPlanIndexSearches = 0
  var accessPlanScans = 0
  var outcome = "incomplete"

  fun fields(): Map<String, Number> = mapOf(
    "section" to section.ordinal,
    "attempts" to attemptCount.coerceIn(0, READ_ATTEMPTS),
    "selected_rows" to selectedRows.coerceAtLeast(0),
    "projection_submitted" to projectionSubmitted.coerceAtLeast(0),
    "projection_returned" to projectionReturned.coerceAtLeast(0),
    "selection_statements" to selectionStatements.coerceAtLeast(0),
    "selection_ns" to selectionNanos.coerceAtLeast(0),
    "projection_ns" to projectionNanos.coerceAtLeast(0),
    "plan_index_searches" to accessPlanIndexSearches.coerceAtLeast(0),
    "plan_scans" to accessPlanScans.coerceAtLeast(0),
    "outcome" to when (outcome) {
      "success" -> 1
      "failure" -> 2
      "cancelled" -> 3
      "thrown" -> 4
      else -> 0
    },
  )
}

private data class PagingQuery(
  val retention: Retention,
  val filter: String,
  val historyFilter: HistoryFilter,
)

/** One live [RoomWorkBoardRepository.observe] collector. Every board observer owns its retention, paging source and cursors. */
private data class Subscription(val section: WorkSection, val queryEpoch: Long)

private class PagingEntry(val query: PagingQuery, val source: BoardPagingSource)

private data class ActiveRetention(
  val retention: Retention,
  val filter: String,
  val historyFilter: HistoryFilter,
  val queryEpoch: Long,
  val generation: Long,
  val session: Long,
  val storeId: String?,
  val revision: Long?,
) {
  /** Same subscription query in the same generation and observation session, whatever store revision it last read. */
  fun sameOwner(other: ActiveRetention): Boolean =
    copy(storeId = null, revision = null) == other.copy(storeId = null, revision = null)
}

/** Board pages for one selected database: SQL-selected candidates, runtime projection, and page/lease bookkeeping. */
@Inject
@SingleIn(DatabaseSessionScope::class)
class RoomWorkBoardRepository(
  private val session: DatabaseSession,
  private val dispatchers: DispatcherProvider,
  private val clock: Clock,
  private val monotonicClock: MonotonicClock,
  private val diagnostics: Diagnostics,
) : WorkBoardRepository {
  private val pagingSources = ConcurrentRegistry<Subscription, PagingEntry>()
  private val queryEpochs = Counter()
  private val liveSubscriptions = ConcurrentRegistry<Subscription, Unit>()
  private val activeRetentions = mutableMapOf<Subscription, ActiveRetention>()
  private val deadlines = mutableMapOf<WorkSection, MutableMap<String, Instant>>()
  private val schedulers = mutableMapOf<Pair<WorkSection, Long>, DeadlineScheduler>()
  private val ownerObservations = mutableMapOf<WorkSection, MutableMap<String, AutoCloseable>>()

  override val refreshEvents: Flow<Set<WorkSection>> get() = session.refreshEvents

  init {
    session.register(
      object : SessionParticipant {
        override fun registrations(): List<OwnedRegistration> =
          ownerObservations.values.flatMap { it.values }.map {
            OwnedRegistration(it, "room_owner_registration_close_failed")
          }

        override fun invalidated(sections: Set<WorkSection>) {
          pagingSources.removeAll { it.section in sections }.forEach { it.source.invalidate() }
        }

        override fun reset(closed: List<AutoCloseable>) {
          ownerObservations.clear()
          deadlines.clear()
          activeRetentions.clear()
        }
      },
    )
  }

  override fun observe(
    section: WorkSection,
    retention: Retention,
    filter: String,
    historyFilter: HistoryFilter,
    anchor: String?,
  ): Flow<Outcome<Board>> {
    val queryEpoch = queryEpochs.next()
    return channelFlow {
      session.acquireSubscriber()
      val scheduler = DeadlineScheduler(clock)
      session.mutex.withLock {
        schedulers[section to queryEpoch] = scheduler
        liveSubscriptions.compute(Subscription(section, queryEpoch)) { }
      }
      try {
        session.sectionSignals.map { it.getValue(section) }.distinctUntilChanged().collect {
          send(loadPage(section, retention, anchor, filter, historyFilter, refresh = true, queryEpoch = queryEpoch))
        }
      } finally {
        withContext(NonCancellable) {
          // Unregister before stopping so a concurrent rearm cannot start a timer that nothing stops.
          session.mutex.withLock {
            schedulers.remove(section to queryEpoch)
            releaseSubscription(section, queryEpoch)
          }
          scheduler.stop()
          session.releaseSubscriber()
        }
      }
    }.flowOn(dispatchers.io)
  }

  /**
   * Releases the owner registrations, deadlines, retention and paging source that one subscriber's query epoch created.
   * Concurrent subscribers to the same section carry their own epoch, so their entries are untouched. Callers hold the session lock.
   */
  private fun releaseSubscription(section: WorkSection, queryEpoch: Long) {
    val subscription = Subscription(section, queryEpoch)
    val prefix = "$section:$queryEpoch:"
    ownerObservations[section]?.let { registrations ->
      registrations.entries.removeAll { (key, registration) ->
        key.startsWith(prefix).also { if (it) registration.closeOwned(diagnostics, "room_owner_registration_close_failed") }
      }
      if (registrations.isEmpty()) ownerObservations.remove(section)
    }
    deadlines[section]?.let { pageDeadlines ->
      pageDeadlines.keys.removeAll { it.startsWith(prefix) }
      if (pageDeadlines.isEmpty()) deadlines.remove(section)
    }
    activeRetentions.remove(subscription)
    liveSubscriptions.remove(subscription)
    pagingSources.remove(subscription)?.source?.invalidate()
  }

  private fun isLive(subscription: Subscription): Boolean = liveSubscriptions[subscription] != null

  /** The earliest deadline among the retained pages one subscription owns, or null when none of them has one. */
  private fun ownerEarliest(section: WorkSection, queryEpoch: Long): Instant? {
    val prefix = "$section:$queryEpoch:"
    return deadlines[section]?.filterKeys { it.startsWith(prefix) }?.values?.minOrNull()
  }

  /**
   * Publishes the subscription's recomputed earliest deadline to its single timer, which runs in [owner]'s scope. Every
   * change to the owned range (a read, an eviction, a refresh, a replacement) calls it. Callers hold the session lock.
   */
  private fun rearm(section: WorkSection, queryEpoch: Long, owner: SessionGeneration) {
    val scheduler = schedulers[section to queryEpoch] ?: return
    val active = activeRetentions[Subscription(section, queryEpoch)]
    scheduler.publish(owner.scope, active?.let { ownerEarliest(section, queryEpoch) }) {
      if (active != null) fireDeadline(section, active)
    }
  }

  /** Reprojects only [section], and only if [owner] still owns it and its earliest deadline is really due. */
  private suspend fun fireDeadline(section: WorkSection, owner: ActiveRetention) {
    session.mutex.withLock {
      val subscription = Subscription(section, owner.queryEpoch)
      val active = activeRetentions[subscription] ?: return@withLock
      val due = ownerEarliest(section, owner.queryEpoch)?.let { it <= clock.now() } == true
      if (active.sameOwner(owner) && isLive(subscription) && due) {
        session.reprojectSection(section, SessionToken(owner.generation, owner.session))
      }
    }
  }

  override suspend fun board(
    section: WorkSection,
    retention: Retention,
    cursor: String?,
    filter: String,
    historyFilter: HistoryFilter,
  ): Outcome<Board> = loadPage(section, retention, cursor, filter, historyFilter, refresh = false)

  override suspend fun refresh() = session.refresh()

  private suspend fun loadPage(
    section: WorkSection,
    retention: Retention,
    cursor: String?,
    filter: String,
    historyFilter: HistoryFilter,
    refresh: Boolean,
    queryEpoch: Long? = null,
  ): Outcome<Board> {
    val readiness = session.read { _ -> session.observationProblem?.let { Outcome.Failure(it) } ?: Outcome.Value(Unit) }
    if (readiness is Outcome.Failure) return readiness
    val effectiveQueryEpoch = queryEpoch ?: cursor?.let { CursorCodec.decodePage(it)?.queryEpoch }?.takeIf { it > 0 }
    val subscription = effectiveQueryEpoch?.let { Subscription(section, it) }
    val newSource = {
      BoardPagingSource { key, rebase -> readPage(section, retention, key, filter, historyFilter, rebase, effectiveQueryEpoch) }
    }
    // A read that belongs to no live subscription gets a throwaway source, so it can never supersede a subscriber's.
    val paging = if (subscription != null && isLive(subscription)) {
      val query = PagingQuery(retention, filter, historyFilter)
      pagingSources.compute(subscription) { current ->
        if (current?.query == query && !current.source.invalid) {
          current
        } else {
          current?.source?.invalidate()
          PagingEntry(query, newSource())
        }
      }.source
    } else {
      newSource()
    }
    val params = if (refresh) {
      PagingSource.LoadParams.Refresh(cursor, 1, false)
    } else {
      PagingSource.LoadParams.Append(cursor ?: "", 1, false)
    }
    val result = paging.load(if (!refresh && cursor == null) PagingSource.LoadParams.Refresh(null, 1, false) else params)
    return when (result) {
      is PagingSource.LoadResult.Page -> Outcome.Value(result.data.single())
      is PagingSource.LoadResult.Error -> Outcome.Failure((result.throwable as BoardPageFailure).problem)
      is PagingSource.LoadResult.Invalid -> session.observationProblem?.let { Outcome.Failure(it) } ?: staleCursor()
    }
  }

  private suspend fun readPage(
    section: WorkSection,
    retention: Retention,
    cursor: String?,
    filter: String,
    historyFilter: HistoryFilter,
    rebase: Boolean,
    requestedQueryEpoch: Long? = null,
  ): Outcome<Board> {
    val accounting = ReadPageAccounting(section)
    return try {
      val outcome = session.read { owner ->
        session.observationProblem?.let { return@read Outcome.Failure(it) }
        val parsed = cursor?.let { CursorCodec.decodePage(it) ?: return@read staleCursor() }
        val generation = session.generation
        if (parsed != null && cursorIsStale(parsed, generation, section, retention, filter, historyFilter, requestedQueryEpoch, rebase)) {
          return@read staleCursor()
        }
        // A read outside any subscription carries epoch 0: its cursors chain freely and nothing is retained for it.
        val queryEpoch = requestedQueryEpoch ?: parsed?.queryEpoch?.takeIf { it > 0 } ?: 0L
        val observedAt = if (rebase) clock.now() else parsed?.observedAt?.let(Instant::parse) ?: clock.now()
        val requestedPageKey = parsed?.let { "$section:$queryEpoch:${it.afterTime}:${it.afterId}" } ?: "$section:$queryEpoch:root"
        val cutoff = observedAt - if (section == WorkSection.RECENT) 24.hours else retention.duration
        var selectionStatements = 0
        var selectedRows = 0
        var projectedItems = 0
        var selectionNanos = 0L
        var projectionNanos = 0L
        val attemptMetrics = mutableListOf<String>()
        repeat(READ_ATTEMPTS) {
          accounting.attemptCount++
          val startObservation = session.workObservation()
          if (parsed != null && !rebase && (parsed.storeId != startObservation.storeId || parsed.storeRevision != startObservation.revision)) {
            return@read staleCursor()
          }
          val result = mutableListOf<WorkItem>()
          var next: CandidateRow? = null
          val attemptRowsBefore = selectedRows
          val attemptProjectedBefore = projectedItems
          val attemptSelectionBefore = selectionNanos
          val attemptProjectionBefore = projectionNanos
          var earliest: Instant? = null
          fun noteDeadline(candidate: Instant) {
            val current = earliest
            if (current == null || candidate < current) earliest = candidate
          }
          var committedObservation: StoreObservation? = startObservation
          val selectionStarted = monotonicClock.nanos()
          val candidates = try {
            val selected = owner.connection.candidates(
              CandidateRequest(
                section, cutoff.toString(), observedAt.toString(), parsed?.afterTime, parsed?.afterId,
                filter, CANDIDATE_BUDGET, historyFilter,
              ),
            )
            accounting.accessPlanIndexSearches = selected.planIndexSearches
            accounting.accessPlanScans = selected.planScans
            selected.rows
          } finally {
            val elapsed = (monotonicClock.nanos() - selectionStarted).coerceAtLeast(0)
            selectionNanos += elapsed
            accounting.selectionNanos += elapsed
            selectionStatements++
            accounting.selectionStatements++
          }
          selectedRows += candidates.size
          accounting.selectedRows += candidates.size
          if (startObservation != session.workObservation()) return@repeat
          val batches = candidates.chunked(CANDIDATE_PAGE_SIZE)
          val projectionStarted = monotonicClock.nanos()
          val projections = try {
            session.gateway.existingSnapshot {
              buildList {
                var accepted = 0
                for (rows in batches) {
                  accounting.projectionSubmitted += rows.size
                  val projection = projectBoard(rows.map { it.id }, retention, observedAt, CANDIDATE_PAGE_SIZE)
                  add(projection)
                  if (projection.items.any { it.incompatible }) break
                  accepted += projection.items.count { belongs(it.item, section, cutoff, observedAt) }
                  if (accepted >= PAGE_SIZE) break
                }
              }
            }
          } finally {
            val elapsed = (monotonicClock.nanos() - projectionStarted).coerceAtLeast(0)
            projectionNanos += elapsed
            accounting.projectionNanos += elapsed
          }
          for ((index, projection) in projections.withIndex()) {
            val rows = batches[index]
            projection.diagnosticReasons.forEach { reason ->
              diagnostics.record("work_data_$reason")
              attemptMetrics += reason
            }
            projectedItems += projection.items.size
            accounting.projectionReturned += projection.items.size
            if (projection.items.any { it.incompatible }) return@read session.failure("incompatible_record")
            committedObservation = projection.observation
            projection.items.mapNotNull { it.freshnessDeadline }.filter { it > clock.now() }.minOrNull()?.let(::noteDeadline)
            projection.items.mapNotNull { it.item.membershipDeadline }.filter { it > clock.now() }.minOrNull()?.let(::noteDeadline)
            val projected = projection.items.map { it.item.copy(databaseGeneration = generation) }.associateBy { it.identity.id }
            for (row in rows) {
              next = row
              val item = projected[row.id]
              if (item != null && belongs(item, section, cutoff, observedAt)) {
                result += item
                if (section in setOf(WorkSection.HISTORY, WorkSection.RECENT)) {
                  val expires = item.finishedAt!! + (if (section == WorkSection.RECENT) 24.hours else retention.duration) + 1.nanoseconds
                  noteDeadline(expires)
                }
              }
              row.deadline?.takeIf { section in setOf(WorkSection.GOALS, WorkSection.PHASES, WorkSection.OPERATIONS, WorkSection.NEEDS_YOU) }?.let { raw ->
                val time = Instant.parse(raw)
                if (time > clock.now()) noteDeadline(time)
              }
              if (result.size == PAGE_SIZE) break
            }
            if (result.size == PAGE_SIZE) break
          }
          val exhausted = next == candidates.lastOrNull() && candidates.size < CANDIDATE_BUDGET
          val coherent = projections.all { it.observation == startObservation }
          val attemptNumber = attemptMetrics.size + 1
          val attemptRows = selectedRows - attemptRowsBefore
          val attemptProjected = projectedItems - attemptProjectedBefore
          attemptMetrics += "selection_attempt=$attemptNumber,rows=$attemptRows,projected=$attemptProjected," +
            "selection_ns=${selectionNanos - attemptSelectionBefore},projection_ns=${projectionNanos - attemptProjectionBefore}," +
            "coherent=$coherent"
          val endObservation = committedObservation ?: startObservation
          if (coherent && committedObservation == startObservation && session.currentFileMatches()) {
            val observationSession = session.observationSession
            val subscription = Subscription(section, queryEpoch)
            if (isLive(subscription)) {
              val retainedObservation = ActiveRetention(
                retention, filter, historyFilter, queryEpoch, generation, observationSession,
                endObservation.storeId, endObservation.revision,
              )
              activeRetentions[subscription] = retainedObservation
              if (section in setOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES, WorkSection.OPERATIONS)) {
                watchOwners(section, requestedPageKey, result, owner, retainedObservation)
              }
              val pageDeadlines = deadlines.getOrPut(section) { mutableMapOf() }
              val pageEarliest = earliest
              if (pageEarliest == null) pageDeadlines.remove(requestedPageKey) else pageDeadlines[requestedPageKey] = pageEarliest
              rearm(section, queryEpoch, owner)
            }
            val nextCursor = next?.takeIf { !exhausted }?.let { row ->
              CursorCodec.encode(
                PageCursor(
                  generation, observationSession, section, observedAt.toString(), retention.cliValue, filter, historyFilter,
                  row.sortTime, row.id, endObservation.storeId, endObservation.revision, queryEpoch,
                ),
              )
            }
            return@read Outcome.Value(
              Board(
                observedAt, retention, result,
                diagnostics = attemptMetrics + listOf(
                  "selection_statements=$selectionStatements", "selection_rows=$selectedRows",
                  "selection_duration_ns=$selectionNanos", "projection_items=$projectedItems",
                  "projection_duration_ns=$projectionNanos",
                  "candidate_plan_index_searches=${accounting.accessPlanIndexSearches}",
                  "candidate_plan_scans=${accounting.accessPlanScans}",
                ),
                nextCursor = nextCursor,
                generation = generation,
                observationSession = observationSession,
                databasePath = session.path,
                storeId = committedObservation?.storeId,
                revision = committedObservation?.revision,
                pageKey = requestedPageKey,
                queryEpoch = queryEpoch,
                filter = filter,
                historyFilter = historyFilter,
              ),
            )
          }
        }
        diagnostics.record("query_snapshot_changed")
        Outcome.Failure(Problem(ProblemCode.BUSY, "Work changed during this read. Retry to get a coherent snapshot.", StorageFailure.BUSY))
      }
      accounting.outcome = if (outcome is Outcome.Value) "success" else "failure"
      outcome
    } catch (error: CancellationException) {
      accounting.outcome = "cancelled"
      throw error
    } catch (error: Exception) {
      accounting.outcome = "thrown"
      throw error
    } finally {
      diagnostics.recordRequest("board_read_${section.name.lowercase()}_${accounting.outcome}", accounting.fields())
    }
  }

  private fun cursorIsStale(
    parsed: PageCursor,
    generation: Long,
    section: WorkSection,
    retention: Retention,
    filter: String,
    historyFilter: HistoryFilter,
    requestedQueryEpoch: Long?,
    rebase: Boolean,
  ): Boolean {
    if (parsed.generation != generation || parsed.session != session.observationSession || parsed.section != section ||
      parsed.retention != retention.cliValue || parsed.filter != filter || parsed.historyFilter != historyFilter
    ) {
      return true
    }
    if (rebase) return false
    if (requestedQueryEpoch != null && parsed.queryEpoch != requestedQueryEpoch) return true
    return parsed.queryEpoch > 0 && !isLive(Subscription(section, parsed.queryEpoch))
  }

  /**
   * Replaces the owner registration for one page so that an owner exit reprojects the section. The callback runs in the
   * generation's scope, so retiring the generation joins it. Callers hold the session lock.
   */
  private fun watchOwners(
    section: WorkSection,
    pageKey: String,
    items: List<WorkItem>,
    owner: SessionGeneration,
    retained: ActiveRetention,
  ) {
    val registrations = ownerObservations.getOrPut(section) { mutableMapOf() }
    registrations.remove(pageKey)?.close()
    registrations[pageKey] = session.gateway.observeOwners(
      items.filter { it.lifecycle == Lifecycle.ACTIVE }.map { it.identity.id },
      clock.now(),
    ) {
      session.launchIn(owner) {
        if (activeRetentions[Subscription(section, retained.queryEpoch)]?.sameOwner(retained) == true) {
          session.reprojectSection(section, SessionToken(retained.generation, retained.session))
        }
      }
    }
  }

  override fun retainPages(retention: BoardRetention) {
    val owner = session.currentGeneration() ?: return
    val retained = retention.pageKeys.toSet()
    session.launchIn(owner) {
      val section = retention.section
      val subscription = Subscription(section, retention.queryEpoch)
      val expected = ActiveRetention(
        retention.retention, retention.filter, retention.historyFilter, retention.queryEpoch,
        retention.generation, retention.observationSession, retention.storeId, retention.revision,
      )
      if (!isLive(subscription) || activeRetentions[subscription] != expected) return@launchIn
      // Other subscribers of the section keep their own pages under their own epoch prefix.
      val prefix = "$section:${retention.queryEpoch}:"
      deadlines[section]?.let { current ->
        current.keys.removeAll { it.startsWith(prefix) && it !in retained }
        if (current.isEmpty()) deadlines.remove(section)
      }
      val registrations = ownerObservations[section]
      registrations?.entries?.removeAll { (key, registration) ->
        if (key.startsWith(prefix) && key !in retained) {
          registration.closeOwned(diagnostics, "room_owner_registration_close_failed")
          true
        } else {
          false
        }
      }
      if (registrations?.isEmpty() == true) ownerObservations.remove(section)
      rearm(section, retention.queryEpoch, owner)
    }
  }

  private fun belongs(item: WorkItem, section: WorkSection, cutoff: Instant, through: Instant): Boolean = when (section) {
    WorkSection.HISTORY, WorkSection.RECENT -> item.finishedAt?.let { it >= cutoff && it <= through } == true
    WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES, WorkSection.OPERATIONS -> item.liveSection == section
  }
}
