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
import dev.skillbill.tray.core.data.session.MonotonicClock
import dev.skillbill.tray.core.data.session.OwnedRegistration
import dev.skillbill.tray.core.data.session.PageCursor
import dev.skillbill.tray.core.data.session.SessionParticipant
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
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
  val queryEpoch: Long?,
)

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
)

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
  private val pagingSources = ConcurrentRegistry<WorkSection, PagingEntry>()
  private val queryEpochs = Counter()
  private val latestQueryEpochs = ConcurrentRegistry<WorkSection, Long>()
  private val deadlineRevision = MutableStateFlow(0L)
  private val activeRetentions = mutableMapOf<WorkSection, ActiveRetention>()
  private val deadlines = mutableMapOf<WorkSection, MutableMap<String, Instant>>()
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
          sections.forEach { pagingSources.remove(it)?.source?.invalidate() }
        }

        override fun reset(closed: List<AutoCloseable>) {
          ownerObservations.clear()
          deadlines.clear()
          activeRetentions.clear()
          latestQueryEpochs.clear()
          deadlineRevision.update { it + 1 }
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
    latestQueryEpochs.compute(section) { queryEpoch }
    return channelFlow {
      session.acquireSubscriber()
      var timer: Job? = null
      try {
        session.sectionSignals.map { it.getValue(section) }.distinctUntilChanged().collect {
          timer?.cancelAndJoin()
          val result = loadPage(section, retention, anchor, filter, historyFilter, refresh = true, queryEpoch = queryEpoch)
          send(result)
          if (result is Outcome.Value) timer = launch { awaitBoardDeadline(section) }
        }
      } finally {
        timer?.cancel()
        withContext(NonCancellable) {
          session.mutex.withLock { releaseSubscription(section, queryEpoch) }
          session.releaseSubscriber()
        }
      }
    }.flowOn(dispatchers.io)
  }

  /**
   * Releases the owner registrations, deadlines, retention and paging source that one subscriber's query epoch created.
   * Later subscribers to the same section carry a newer epoch, so their entries are untouched. Callers hold the session lock.
   */
  private fun releaseSubscription(section: WorkSection, queryEpoch: Long) {
    val prefix = "$section:$queryEpoch:"
    ownerObservations[section]?.let { registrations ->
      registrations.entries.removeAll { (key, registration) ->
        key.startsWith(prefix).also { if (it) registration.closeOwned(diagnostics, "room_owner_registration_close_failed") }
      }
      if (registrations.isEmpty()) ownerObservations.remove(section)
    }
    deadlines[section]?.let { pageDeadlines ->
      if (pageDeadlines.keys.removeAll { it.startsWith(prefix) }) deadlineRevision.update { it + 1 }
      if (pageDeadlines.isEmpty()) deadlines.remove(section)
    }
    if (activeRetentions[section]?.queryEpoch == queryEpoch) activeRetentions.remove(section)
    if (latestQueryEpochs[section] == queryEpoch) pagingSources.remove(section)?.source?.invalidate()
  }

  private suspend fun awaitBoardDeadline(section: WorkSection) {
    var deadlineVersion = deadlineRevision.value
    while (currentCoroutineContext().isActive) {
      val nextDeadline = session.mutex.withLock { deadlines[section]?.values?.minOrNull() }
      if (nextDeadline == null) {
        deadlineVersion = deadlineRevision.first { it != deadlineVersion }
        continue
      }
      val wait = waitMillis(nextDeadline - clock.now())
      val changed = withTimeoutOrNull(wait) { deadlineRevision.first { it != deadlineVersion } }
      if (changed != null) {
        deadlineVersion = changed
        continue
      }
      session.invalidate(
        if (section in setOf(WorkSection.GOALS, WorkSection.PHASES)) {
          setOf(section, WorkSection.NEEDS_YOU)
        } else {
          setOf(section)
        },
      )
      return
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
    val readiness = session.mutex.withLock {
      session.safeRead {
        session.ensureSession()
        session.observationProblem?.let { Outcome.Failure(it) } ?: Outcome.Value(Unit)
      }
    }
    if (readiness is Outcome.Failure) return readiness
    val effectiveQueryEpoch = queryEpoch ?: latestQueryEpochs[section]
    val query = PagingQuery(retention, filter, historyFilter, effectiveQueryEpoch)
    val paging = pagingSources.compute(section) { current ->
      if (current?.query == query && !current.source.invalid) {
        current
      } else {
        current?.source?.invalidate()
        PagingEntry(
          query,
          BoardPagingSource { key, rebase ->
            readPage(section, retention, key, filter, historyFilter, rebase, effectiveQueryEpoch)
          },
        )
      }
    }.source
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
      val outcome = session.mutex.withLock {
        session.safeRead {
          session.ensureSession()
          session.observationProblem?.let { return@safeRead Outcome.Failure(it) }
          val parsed = cursor?.let { CursorCodec.decodePage(it) ?: return@safeRead staleCursor() }
          val generation = session.generation
          if (parsed != null && cursorIsStale(parsed, generation, section, retention, filter, historyFilter, requestedQueryEpoch, rebase)) {
            return@safeRead staleCursor()
          }
          val queryEpoch = requestedQueryEpoch ?: parsed?.queryEpoch?.takeIf { it > 0 } ?: queryEpochs.next()
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
              return@safeRead staleCursor()
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
              val selected = session.requireConnection().candidates(
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
              if (projection.items.any { it.incompatible }) return@safeRead session.failure("incompatible_record")
              committedObservation = projection.observation
              projection.items.mapNotNull { it.freshnessDeadline }.filter { it > clock.now() }.minOrNull()?.let(::noteDeadline)
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
                row.deadline?.takeIf { section in setOf(WorkSection.GOALS, WorkSection.PHASES, WorkSection.NEEDS_YOU) }?.let { raw ->
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
              val retainedObservation = ActiveRetention(
                retention, filter, historyFilter, queryEpoch, generation, observationSession,
                endObservation.storeId, endObservation.revision,
              )
              activeRetentions[section] = retainedObservation
              if (section in setOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES)) {
                watchOwners(section, requestedPageKey, result, generation, retainedObservation)
              }
              val pageDeadlines = deadlines.getOrPut(section) { mutableMapOf() }
              val pageEarliest = earliest
              if (pageEarliest == null) pageDeadlines.remove(requestedPageKey) else pageDeadlines[requestedPageKey] = pageEarliest
              deadlineRevision.update { it + 1 }
              val nextCursor = next?.takeIf { !exhausted }?.let { row ->
                CursorCodec.encode(
                  PageCursor(
                    generation, observationSession, section, observedAt.toString(), retention.cliValue, filter, historyFilter,
                    row.sortTime, row.id, endObservation.storeId, endObservation.revision, queryEpoch,
                  ),
                )
              }
              return@safeRead Outcome.Value(
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
    return latestQueryEpochs[section]?.let { it != parsed.queryEpoch } == true
  }

  /** Replaces the owner registration for one page so that an owner exit invalidates the section. Callers hold the session lock. */
  private fun watchOwners(
    section: WorkSection,
    pageKey: String,
    items: List<WorkItem>,
    ownerGeneration: Long,
    retained: ActiveRetention,
  ) {
    val registrations = ownerObservations.getOrPut(section) { mutableMapOf() }
    registrations.remove(pageKey)?.close()
    registrations[pageKey] = session.gateway.observeOwners(
      items.filter { it.lifecycle == Lifecycle.ACTIVE }.map { it.identity.id },
      clock.now(),
    ) {
      session.scope.launch {
        session.mutex.withLock {
          if (ownerGeneration == session.generation && activeRetentions[section] == retained && !session.closed) {
            session.invalidate(setOf(section, WorkSection.NEEDS_YOU))
          }
        }
      }
    }
  }

  override fun retainPages(retention: BoardRetention) {
    val retained = retention.pageKeys.toSet()
    session.scope.launch {
      session.mutex.withLock {
        val section = retention.section
        if (latestQueryEpochs[section]?.let { it != retention.queryEpoch } == true) return@withLock
        val expected = ActiveRetention(
          retention.retention, retention.filter, retention.historyFilter, retention.queryEpoch,
          retention.generation, retention.observationSession, retention.storeId, retention.revision,
        )
        if (activeRetentions[section] != expected) return@withLock
        val current = deadlines[section]
        if (current != null) {
          if (current.keys.retainAll(retained)) deadlineRevision.update { it + 1 }
          if (current.isEmpty()) deadlines.remove(section)
        }
        val registrations = ownerObservations[section]
        registrations?.entries?.removeAll { (key, registration) ->
          if (key !in retained) {
            registration.closeOwned(diagnostics, "room_owner_registration_close_failed")
            true
          } else {
            false
          }
        }
        if (registrations?.isEmpty() == true) ownerObservations.remove(section)
      }
    }
  }

  private fun belongs(item: WorkItem, section: WorkSection, cutoff: Instant, through: Instant): Boolean = when (section) {
    WorkSection.HISTORY, WorkSection.RECENT -> item.finishedAt?.let { it >= cutoff && it <= through } == true
    WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES -> item.liveSection == section
  }
}

internal fun waitMillis(remaining: Duration): Long {
  val nanos = remaining.inWholeNanoseconds
  return (nanos.floorDiv(1_000_000L) + if (nanos % 1_000_000L == 0L) 0 else 1).coerceAtLeast(1)
}
