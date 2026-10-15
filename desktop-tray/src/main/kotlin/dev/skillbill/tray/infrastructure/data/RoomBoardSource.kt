package dev.skillbill.tray.infrastructure.data

import androidx.paging.PagingSource
import androidx.sqlite.SQLiteConnection
import dev.skillbill.tray.application.*
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import dev.skillbill.tray.infrastructure.RuntimeDiagnostics
import dev.skillbill.tray.infrastructure.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import skillbill.di.core.*
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.engine.work.control.model.WorkControlAction
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlTarget
import skillbill.error.core.databaseIncompatible
import skillbill.error.core.databaseMissing
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.model.EnvironmentContext
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardWindow
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.work.model.WorkStoreObservation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Base64

@Serializable
private data class PageCursor(val generation: Long, val session: Long, val section: WorkSection,
  val observedAt: String, val retention: String, val filter: String, val historyFilter: HistoryFilter, val afterTime: String, val afterId: String,
  val storeId: String? = null, val storeRevision: Long? = null, val queryEpoch: Long = 0)

@Serializable
private data class DetailCursor(val generation: Long, val session: Long, val revision: Long,
  val id: String, val repositoryIdentity: String, val runtimeCursor: String, val list: DetailList? = null)

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
    "attempts" to attemptCount.coerceIn(0, 3),
    "selected_rows" to selectedRows.coerceAtLeast(0),
    "projection_submitted" to projectionSubmitted.coerceAtLeast(0),
    "projection_returned" to projectionReturned.coerceAtLeast(0),
    "selection_statements" to selectionStatements.coerceAtLeast(0),
    "selection_ns" to selectionNanos.coerceAtLeast(0),
    "projection_ns" to projectionNanos.coerceAtLeast(0),
    "plan_index_searches" to accessPlanIndexSearches.coerceAtLeast(0),
    "plan_scans" to accessPlanScans.coerceAtLeast(0),
    "outcome" to when (outcome) { "success" -> 1; "failure" -> 2; "cancelled" -> 3; "thrown" -> 4; else -> 0 },
  )
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomBoardSource internal constructor(
  private val environment: Map<String, String>,
  private val userHome: Path,
  private val repositoryRoot: Path,
  private val clock: Clock = Clock.systemUTC(),
  private val monotonicNanos: () -> Long = System::nanoTime,
  private val recordReadWork: () -> Unit = {},
  private val observeChanges: (Path, CompletableDeferred<Unit>) -> Flow<StoreChange> = { selected, ready ->
    DesktopCommitSignalObserver(selected).changes(ready)
  },
) : WorkBoardRepository, WorkDetailRepository, WorkControlRepository, WorkStoreSession {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private val signals = MutableStateFlow(WorkSection.entries.associateWith { 0L })
  private val detailSignals = MutableStateFlow(mapOf(WorkSection.GOALS to 0L, WorkSection.PHASES to 0L))
  private val deadlineRevision = MutableStateFlow(0L)
  private val mutableRefreshEvents = MutableSharedFlow<Set<WorkSection>>(extraBufferCapacity = 1)
  override val refreshEvents: Flow<Set<WorkSection>> = mutableRefreshEvents.asSharedFlow()
  private var component: RuntimeComponent? = null
  private var path: Path? = null
  private var room: WorkRoomDatabase? = null
  private var revisionConnection: SQLiteConnection? = null
  private var fileIdentity: String? = null
  private var generation = 0L
  private var session = 0L
  @Volatile private var closed = false
  @Volatile private var closeCompletion: CompletableDeferred<Unit>? = null
  private var observer: Job? = null
  @Volatile
  private var observationProblem: Problem? = null
  private var configurationProblem: Problem? = null
  private data class PagingQuery(val retention: Retention, val filter: String, val historyFilter: HistoryFilter, val queryEpoch: Long?)
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
  private data class SessionTeardown(
    val observer: Job?,
    val room: WorkRoomDatabase?,
    val revisionConnection: SQLiteConnection?,
    val detailOwner: AutoCloseable?,
    val ownerRegistrations: List<AutoCloseable>,
  )
  private val pagingSources = java.util.concurrent.ConcurrentHashMap<WorkSection, Pair<PagingQuery, BoardPagingSource>>()
  private var detailOwnerObservation: AutoCloseable? = null
  private var detailObservationToken = 0L
  private val ownerObservations = mutableMapOf<WorkSection, MutableMap<String, AutoCloseable>>()
  private val deadlines = mutableMapOf<WorkSection, MutableMap<String, Instant>>()
  private val queryEpochs = java.util.concurrent.atomic.AtomicLong()
  private val activeRetentions = mutableMapOf<WorkSection, ActiveRetention>()
  private val latestQueryEpochs = java.util.concurrent.ConcurrentHashMap<WorkSection, Long>()
  private val accessPlans = mutableMapOf<Triple<Long, WorkSection, HistoryFilter>, Pair<Int, Int>>()

  override suspend fun selectDatabase(path: String?) = mutex.withLock {
    check(!closed)
    disposeSession()
    component = null
    this.path = null
    configurationProblem = null
    try {
    component = RuntimeComponent::class.create(RuntimeContext(
      EnvironmentContext(path, environment = environment, userHome = userHome, repositoryRoot = repositoryRoot),
      TransportContext(), WorkflowOpsContext(), OptionalCallbacks()))
    this.path = component!!.databaseSession.resolveDbPath()
    prepareSchema(requireNotNull(component))
    } catch (_: java.nio.file.InvalidPathException) {
      configurationProblem = Problem(ProblemCode.DATABASE_ACCESS, "The selected database path is invalid.", StorageFailure.ACCESS)
      RuntimeDiagnostics.record("database_path_invalid")
    }

    generation++
    observationProblem = null
    invalidate()
  }

  override fun observe(section: WorkSection, retention: Retention, filter: String, historyFilter: HistoryFilter, anchor: String?): Flow<Outcome<Board>> {
    val queryEpoch = queryEpochs.incrementAndGet()
    latestQueryEpochs[section] = queryEpoch
    return channelFlow {
      var timer: Job? = null
      try {
        signals.map { it.getValue(section) }.distinctUntilChanged().collect {
          timer?.cancelAndJoin()
          val result = loadPage(section, retention, anchor, filter, historyFilter, refresh = true, queryEpoch = queryEpoch)
          send(result)
          if (result is Outcome.Value) timer = launch { awaitBoardDeadline(section) }
        }
      } finally {
        timer?.cancel()
      }
    }.flowOn(Dispatchers.IO)
  }

  private suspend fun awaitBoardDeadline(section: WorkSection) {
    var deadlineVersion = deadlineRevision.value
    while (currentCoroutineContext().isActive) {
      val nextDeadline = mutex.withLock { deadlines[section]?.values?.minOrNull() }
      if (nextDeadline == null) {
        deadlineVersion = deadlineRevision.first { it != deadlineVersion }
        continue
      }
      val remaining = java.time.Duration.between(clock.instant(), nextDeadline)
      val wait = (remaining.toMillis() + if (remaining.nano % 1_000_000 == 0) 0 else 1).coerceAtLeast(1)
      val changed = withTimeoutOrNull(wait) { deadlineRevision.first { it != deadlineVersion } }
      if (changed != null) {
        deadlineVersion = changed
        continue
      }
      invalidate(if (section in setOf(WorkSection.GOALS, WorkSection.PHASES)) setOf(section, WorkSection.NEEDS_YOU) else setOf(section))
      return
    }
  }

  override suspend fun board(section: WorkSection, retention: Retention, cursor: String?, filter: String, historyFilter: HistoryFilter): Outcome<Board> = loadPage(section, retention, cursor, filter, historyFilter, refresh = false)

  override fun observeDetail(identity: WorkIdentity): Flow<Outcome<Detail>> =
    flow {
      coroutineScope {
        val token = mutex.withLock { ++detailObservationToken }
        var timer: Job? = null
        try {
          var relevant = setOf(WorkSection.GOALS, WorkSection.PHASES)
          var previous: Map<WorkSection, Long>? = null
          detailSignals.collect { revisions ->
            val changed = previous == null || relevant.any { previous?.get(it) != revisions[it] }
            previous = revisions
            if (changed) {
              timer?.cancelAndJoin()
              val result = detail(identity)
              var nextDeadline: Instant? = null
              if (result is Outcome.Value) {
                relevant = if (result.value.item.family == WorkFamily.FEATURE_GOAL) {
                  setOf(WorkSection.GOALS, WorkSection.PHASES)
                } else setOf(WorkSection.PHASES)
                mutex.withLock {
                  if (token == detailObservationToken && result.value.item.databaseGeneration == generation) {
                    detailOwnerObservation?.close()
                    val ownerGeneration = generation
                    val registration = requireNotNull(component).workObservationService.observeOwners(
                      listOf(identity.id).takeIf { result.value.item.lifecycle == Lifecycle.ACTIVE }.orEmpty(), clock.instant()) {
                      if (token == detailObservationToken && ownerGeneration == generation && !closed) {
                        invalidate(setOf(WorkSection.NEEDS_YOU))
                      }
                    }
                    detailOwnerObservation = registration
                    nextDeadline = listOfNotNull(registration.nextLeaseExpiry, result.value.freshnessDeadline)
                      .filter { it > clock.instant() }.minOrNull()
                  }
                }
              }
              emit(result)
              nextDeadline?.let { deadline ->
                timer = launch {
                  val remaining = java.time.Duration.between(clock.instant(), deadline)
                  delay((remaining.toMillis() + if (remaining.nano % 1_000_000 == 0) 0 else 1).coerceAtLeast(1))
                  if (token == detailObservationToken && !closed) invalidate(setOf(WorkSection.NEEDS_YOU))
                }
              }
            }
          }
        } finally {
          withContext(NonCancellable) {
            timer?.cancelAndJoin()
            mutex.withLock {
              if (token == detailObservationToken) {
                detailOwnerObservation?.close()
                detailOwnerObservation = null
              }
            }
          }
        }
      }
    }.flowOn(Dispatchers.IO)

  private suspend fun loadPage(section: WorkSection, retention: Retention, cursor: String?, filter: String,
    historyFilter: HistoryFilter, refresh: Boolean, queryEpoch: Long? = null): Outcome<Board> {
    val readiness = mutex.withLock {
      safeRead {
        ensureSession(requireNotNull(component))
        observationProblem?.let { Outcome.Failure(it) } ?: Outcome.Value(Unit)
      }
    }
    if (readiness is Outcome.Failure) return readiness
    val effectiveQueryEpoch = queryEpoch ?: latestQueryEpochs[section]
    val query = PagingQuery(retention, filter, historyFilter, effectiveQueryEpoch)
    val paging = pagingSources.compute(section) { _, current ->
      if (current?.first == query && !current.second.invalid) current else {
        current?.second?.invalidate()
        query to BoardPagingSource { key, rebase -> readPage(section, retention, key, filter, historyFilter, rebase, effectiveQueryEpoch) }
      }
    }!!.second
    val params = if (refresh) PagingSource.LoadParams.Refresh(cursor, 1, false)
      else PagingSource.LoadParams.Append(cursor ?: "", 1, false)
    val result = paging.load(if (!refresh && cursor == null) PagingSource.LoadParams.Refresh(null, 1, false) else params)
    return when (result) {
      is PagingSource.LoadResult.Page -> Outcome.Value(result.data.single())
      is PagingSource.LoadResult.Error -> Outcome.Failure((result.throwable as BoardPageFailure).problem)
      is PagingSource.LoadResult.Invalid -> observationProblem?.let { Outcome.Failure(it) } ?: staleCursor()
    }
  }

  private suspend fun readPage(section: WorkSection, retention: Retention, cursor: String?, filter: String,
    historyFilter: HistoryFilter, rebase: Boolean, requestedQueryEpoch: Long? = null): Outcome<Board> {
    val accounting = ReadPageAccounting(section)
    return try {
      val outcome = mutex.withLock {
      safeRead {
        configurationProblem?.let { return@safeRead Outcome.Failure(it) }
        val runtime = requireNotNull(component)
        ensureSession(runtime)
        observationProblem?.let { return@safeRead Outcome.Failure(it) }
        val parsed = cursor?.let {
          try { Json.decodeFromString<PageCursor>(Base64.getUrlDecoder().decode(it).decodeToString()) }
          catch (_: SerializationException) { return@safeRead staleCursor() }
          catch (_: IllegalArgumentException) { return@safeRead staleCursor() }
        }
        if (parsed != null && (parsed.generation != generation || parsed.session != session || parsed.section != section ||
            parsed.retention != retention.cliValue || parsed.filter != filter || parsed.historyFilter != historyFilter ||
            requestedQueryEpoch != null && parsed.queryEpoch != requestedQueryEpoch && !rebase)) return@safeRead staleCursor()
        if (parsed != null && latestQueryEpochs[section]?.let { it != parsed.queryEpoch } == true && !rebase) {
          return@safeRead staleCursor()
        }
        val queryEpoch = requestedQueryEpoch ?: parsed?.queryEpoch?.takeIf { it > 0 } ?: queryEpochs.incrementAndGet()
        val observedAt = if (rebase) clock.instant() else parsed?.observedAt?.let(Instant::parse) ?: clock.instant()
        val requestedPageKey = parsed?.let { "${section}:${queryEpoch}:${it.afterTime}:${it.afterId}" }
          ?: "${section}:${queryEpoch}:root"
        val cutoff = observedAt.minus(if (section == WorkSection.RECENT) java.time.Duration.ofHours(24) else retention.duration)
        var selectionStatements = 0
        var selectedRows = 0
        var projectedItems = 0
        var selectionNanos = 0L
        var projectionNanos = 0L
        val attemptMetrics = mutableListOf<String>()
        repeat(3) {
          accounting.attemptCount++
          val startObservation = workObservation(runtime)
          if (parsed != null && !rebase && (parsed.storeId != startObservation.storeId ||
              parsed.storeRevision != startObservation.revision)) return@safeRead staleCursor()
          val result = mutableListOf<WorkItem>()
          var next: WorkCandidateRow? = null
          val attemptRowsBefore = selectedRows
          val attemptProjectedBefore = projectedItems
          val attemptSelectionBefore = selectionNanos
          val attemptProjectionBefore = projectionNanos
          var earliest: Instant? = null
          var committedObservation: WorkStoreObservation? = startObservation
          val selectionStarted = monotonicNanos()
          val candidates = try {
            val query = candidateQuery(section, cutoff.toString(), observedAt.toString(),
              parsed?.afterTime, parsed?.afterId, filter, CANDIDATE_BUDGET, historyFilter)
            val shape = Triple(session, section, historyFilter)
            val plan = accessPlans.getOrPut(shape) { queryPlan(query.sql) }
            accounting.accessPlanIndexSearches = plan.first
            accounting.accessPlanScans = plan.second
            room!!.candidates().candidates(query.roomQuery)
          } finally {
            val elapsed = (monotonicNanos() - selectionStarted).coerceAtLeast(0)
            selectionNanos += elapsed
            accounting.selectionNanos += elapsed
            selectionStatements++
            accounting.selectionStatements++
          }
          selectedRows += candidates.size
          accounting.selectedRows += candidates.size
          if (startObservation != workObservation(runtime)) return@repeat
          val batches = candidates.chunked(CANDIDATE_PAGE_SIZE)
          val projectionStarted = monotonicNanos()
          val projections = try {
            existingSnapshot(runtime) {
              buildList {
                var accepted = 0
                for (rows in batches) {
                  accounting.projectionSubmitted += rows.size
                  val projection = runtime.ideStatusService.projectBoardSelection(rows.map { it.id }, WorkBoardRequest(
                    WorkBoardWindow.entries.single { it.wireValue == retention.cliValue }, observedAt, CANDIDATE_PAGE_SIZE))
                  add(projection)
                  if (projection.items.any { it.snapshot.problem != null }) break
                  accepted += projection.items.count { belongs(it.toDomain(), section, cutoff, observedAt) }
                  if (accepted >= PAGE_SIZE) break
                }
              }
            }
          } finally {
            val elapsed = (monotonicNanos() - projectionStarted).coerceAtLeast(0)
            projectionNanos += elapsed
            accounting.projectionNanos += elapsed
          }
          for ((index, projection) in projections.withIndex()) {
            val rows = batches[index]
            projection.diagnostics.map { it.reason }.distinct().forEach { reason ->
              RuntimeDiagnostics.record("work_data_${reason.wireValue}")
              attemptMetrics += reason.wireValue
            }
            projectedItems += projection.items.size
            accounting.projectionReturned += projection.items.size
            if (projection.items.any { it.snapshot.problem != null }) return@safeRead failure("incompatible_record")
            committedObservation = projection.observation
            projection.items.mapNotNull { it.freshnessDeadline }.filter { it > clock.instant() }.minOrNull()?.let { deadline ->
              if (earliest == null || deadline < earliest) earliest = deadline
            }
            val projected = projection.items.map { it.toDomain().copy(databaseGeneration = generation) }.associateBy { it.identity.id }
            for (row in rows) {
              next = row
              val item = projected[row.id]
              if (item != null && belongs(item, section, cutoff, observedAt)) {
                result += item
                if (section in setOf(WorkSection.HISTORY, WorkSection.RECENT)) {
                  val expires = item.finishedAt!!.plus(if (section == WorkSection.RECENT) java.time.Duration.ofHours(24) else retention.duration).plusNanos(1)
                  if (earliest == null || expires < earliest) earliest = expires
                }
              }
              row.deadline?.takeIf { section in setOf(WorkSection.GOALS, WorkSection.PHASES, WorkSection.NEEDS_YOU) }?.let { raw ->
                val time = Instant.parse(raw)
                if (time.isAfter(clock.instant()) && (earliest == null || time < earliest)) earliest = time
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
          attemptMetrics += "selection_attempt=$attemptNumber,rows=$attemptRows,projected=$attemptProjected,selection_ns=${selectionNanos - attemptSelectionBefore},projection_ns=${projectionNanos - attemptProjectionBefore},coherent=$coherent"
          val endObservation = committedObservation ?: startObservation
          if (coherent && committedObservation == startObservation && currentFileMatches()) {
            val retainedObservation = ActiveRetention(
              retention, filter, historyFilter, queryEpoch, generation, session, endObservation.storeId, endObservation.revision,
            )
            activeRetentions[section] = retainedObservation
            if (section in setOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES)) {
              val ownerGeneration = generation
              val registrations = ownerObservations.getOrPut(section) { mutableMapOf() }
              registrations.remove(requestedPageKey)?.close()
              registrations[requestedPageKey] = runtime.workObservationService.observeOwners(result.filter { it.lifecycle == Lifecycle.ACTIVE }.map { it.identity.id }, clock.instant()) {
                scope.launch {
                  mutex.withLock {
                    if (ownerGeneration == generation && activeRetentions[section] == retainedObservation && !closed) {
                      invalidate(setOf(section, WorkSection.NEEDS_YOU))
                    }
                  }
                }
              }
            }
            val pageDeadlines = deadlines.getOrPut(section) { mutableMapOf() }
            val pageKey = requestedPageKey
            if (earliest == null) pageDeadlines.remove(pageKey) else pageDeadlines[pageKey] = earliest
            deadlineRevision.update { it + 1 }
            val nextCursor = next?.takeIf { !exhausted }?.let { row ->
              Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(PageCursor(generation, session,
                section, observedAt.toString(), retention.cliValue, filter, historyFilter, row.sort_time, row.id,
                endObservation.storeId, endObservation.revision, queryEpoch)).encodeToByteArray())
            }
            return@safeRead Outcome.Value(Board(observedAt, retention, result, diagnostics = attemptMetrics + listOf(
              "selection_statements=$selectionStatements", "selection_rows=$selectedRows", "selection_duration_ns=$selectionNanos",
              "projection_items=$projectedItems", "projection_duration_ns=$projectionNanos",
              "candidate_plan_index_searches=${accounting.accessPlanIndexSearches}",
              "candidate_plan_scans=${accounting.accessPlanScans}"), nextCursor = nextCursor, generation = generation, observationSession = session, databasePath = path.toString(), storeId = committedObservation?.storeId, revision = committedObservation?.revision, pageKey = pageKey, queryEpoch = queryEpoch, filter = filter, historyFilter = historyFilter))
          }
        }
        RuntimeDiagnostics.record("query_snapshot_changed")
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
      RuntimeDiagnostics.recordRequest("board_read_${section.name.lowercase()}_${accounting.outcome}", accounting.fields())
    }
  }

  private fun queryPlan(sql: String): Pair<Int, Int> = try {
    revisionConnection!!.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
      var indexSearches = 0
      var scans = 0
      while (statement.step()) {
        val detail = statement.getText(3).orEmpty().uppercase()
        if ("SEARCH" in detail && "INDEX" in detail) indexSearches++
        if ("SCAN" in detail) scans++
      }
      indexSearches to scans
    }
  } catch (failure: Exception) {
    RuntimeDiagnostics.record("candidate_query_plan_unavailable_${failure.javaClass.simpleName.take(32)}")
    0 to 0
  }

  override suspend fun detail(identity: WorkIdentity, cursor: String?, list: DetailList?, startOffset: Int): Outcome<Detail> = mutex.withLock {
    safeRead {
      val runtime = requireNotNull(component)
      ensureSession(runtime)
      val parsed = cursor?.let {
        try { Json.decodeFromString<DetailCursor>(Base64.getUrlDecoder().decode(it).decodeToString()) }
        catch (_: SerializationException) { return@safeRead staleCursor() }
        catch (_: IllegalArgumentException) { return@safeRead staleCursor() }
      }
      if (parsed != null && (parsed.generation != generation || parsed.session != session ||
        parsed.revision != workObservation(runtime).revision || parsed.id != identity.id ||
        parsed.repositoryIdentity != identity.repositoryIdentity || parsed.list != list)) return@safeRead staleCursor()
      repeat(3) {
        val before = workObservation(runtime).revision
        val loaded = existingSnapshot(runtime) {
          val result = runtime.ideStatusService.detail(WorkDetailRequest(identity.id, observedAt = clock.instant(), cursor = parsed?.runtimeCursor, pageSize = PAGE_SIZE, startOffset = startOffset, list = list?.let { WorkDetailList.valueOf(it.name) }))
          val options = result.item?.let { work ->
            val domain = work.snapshot.toDomain(work.repositoryRoot, work.finishedAt, work.finishedAtSource?.wireValue)
              .copy(databaseFileObservation = work.databaseFileObservation)
            domain.runTarget()?.let(runtime.workControlService::options)
          }
          result to options
        }
        val result = loaded.first
        if (currentFileMatches()) {
          val item = result.item ?: return@safeRead Outcome.Failure(Problem(ProblemCode.fromWire(result.problem?.code?.wireValue ?: "unknown_work_item"), "This work item is no longer available."))
          fun token(selected: DetailList?): String? = (if (selected == null) result.nextCursor else result.listCursors[WorkDetailList.valueOf(selected.name)])?.let { runtimeCursor ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(DetailCursor(generation,
              session, before, identity.id, identity.repositoryIdentity, runtimeCursor, selected)).encodeToByteArray())
          }
          val cursors = item.moreLists.map { DetailList.valueOf(it.name) }.associateWith { token(it)!! }
          val mapped = item.toDomain(result.observedAt, token(null)).let { it.copy(
            item = it.item.copy(databaseGeneration = generation), cursors = cursors) }
          if (mapped.item.identity != identity) return@safeRead staleCursor()
          return@safeRead Outcome.Value(mapped.copy(runOptions = loaded.second?.let {
            RunOptions(it.canCancel, it.canLaunch, it.launchLabel, it.agentId, it.agentChoices, it.unavailableReason)
          }))
        }
      }
      Outcome.Failure(Problem(ProblemCode.BUSY, "Detail changed during this read. Retry.", StorageFailure.BUSY))
    }
  }

  override suspend fun refresh() = mutex.withLock {
    if (observationProblem != null || observer?.isActive != true) disposeSession()
    if (configurationProblem != null) component?.let { prepareSchema(it) }
    observationProblem = null
    invalidate()
  }

  private suspend fun prepareSchema(runtime: RuntimeComponent) {
    configurationProblem = null
    configurationProblem = (safeRead {
      runtime.databaseSchema.migrateExistingDatabase()
      Outcome.Value(Unit)
    } as? Outcome.Failure)?.problem
  }

  override fun retainPages(retention: BoardRetention) {
    val retained = retention.pageKeys.toSet()
    scope.launch {
      mutex.withLock {
        val section = retention.section
        if (latestQueryEpochs[section]?.let { it != retention.queryEpoch } == true) return@withLock
        val expected = ActiveRetention(retention.retention, retention.filter, retention.historyFilter, retention.queryEpoch,
          retention.generation, retention.observationSession, retention.storeId, retention.revision)
        if (activeRetentions[section] != expected) return@withLock
        val current = deadlines[section]
        if (current != null) {
          if (current.keys.retainAll(retained)) deadlineRevision.update { it + 1 }
          if (current.isEmpty()) deadlines.remove(section)
        }
        val registrations = ownerObservations[section]
        registrations?.entries?.removeAll { (key, registration) ->
          if (key !in retained) {
            registration.closeOwned("room_owner_registration_close_failed")
            true
          } else false
        }
        if (registrations?.isEmpty() == true) ownerObservations.remove(section)
      }
    }
  }

  override suspend fun pause(item: WorkItem): Outcome<Unit> = control(item, false)
  override suspend fun stop(item: WorkItem): Outcome<Unit> = control(item, true)

  private suspend fun control(item: WorkItem, stop: Boolean): Outcome<Unit> = mutex.withLock {
    safeRead {
      val runtime = requireNotNull(component)
      ensureSession(runtime)
      if (!currentFileMatches() || item.databaseGeneration != generation) return@safeRead staleCursor()
      val issue = item.issueKey ?: return@safeRead staleCursor()
      val fileObservation = item.databaseFileObservation ?: return@safeRead staleCursor()
      val target = WorkControlTarget(item.identity.id, item.identity.repositoryIdentity, Path.of(item.repositoryRoot),
        issue, item.executionId, item.statusRevision, item.statusStoreId, item.runSequence, item.updatedAt, item.controlLeaseOwner, item.controlLeaseGeneration,
        item.databaseStoreId?.let { WorkStoreObservation(it, requireNotNull(item.databaseRevision)) }, fileObservation)
      when (val result = runtime.workControlService.request(target, if (stop) WorkControlAction.STOP else WorkControlAction.PAUSE) {
        currentFileMatches() && item.databaseGeneration == generation
      }) {
          WorkControlResult.Accepted -> Outcome.Value(Unit)
          is WorkControlResult.LaunchFailed -> Outcome.Failure(Problem(ProblemCode.UNKNOWN, result.reason))
          is WorkControlResult.Rejected -> {
            RuntimeDiagnostics.record("goal_control_${result.reason.name.lowercase()}")
            Outcome.Failure(Problem(ProblemCode.STALE_TARGET, "This goal changed or cannot accept this control. Refresh before retrying.", StorageFailure.STALE_QUERY))
          }
      }
    }
  }

  override suspend fun cancel(item: WorkItem): Outcome<Unit> = runAction(item, null, true)
  override suspend fun launch(item: WorkItem, agentId: String?): Outcome<Unit> = runAction(item, agentId, false)

  private suspend fun runAction(item: WorkItem, agentId: String?, cancel: Boolean): Outcome<Unit> = mutex.withLock {
    safeRead {
      val runtime = requireNotNull(component)
      ensureSession(runtime)
      if (!currentFileMatches() || item.databaseGeneration != generation) return@safeRead staleCursor()
      val target = item.runTarget() ?: return@safeRead staleCursor()
      val result = if (cancel) runtime.workControlService.request(target, WorkControlAction.CANCEL) else runtime.workControlService.request(target, WorkControlAction.LAUNCH, agentId)
      when (result) {
        WorkControlResult.Accepted -> Outcome.Value(Unit)
        is WorkControlResult.LaunchFailed -> {
          RuntimeDiagnostics.record("run_launch_failed")
          Outcome.Failure(Problem(ProblemCode.UNKNOWN, result.reason))
        }
        is WorkControlResult.Rejected -> {
          RuntimeDiagnostics.record("run_action_${result.reason.name.lowercase()}")
          val message = when (result.reason) {
            WorkControlRejection.AGENT_REQUIRED -> "Choose an agent to start this run."
            WorkControlRejection.BRANCH_CHANGED -> "Check out this run's recorded branch before starting it again."
            WorkControlRejection.TERMINATION_INCOMPLETE -> "The runner has not stopped yet. Refresh and retry cancellation."
            WorkControlRejection.OWNERSHIP_MISMATCH -> "The runner's identity could not be confirmed. Refresh before retrying."
            else -> "This run changed or cannot accept this action. Refresh before retrying."
          }
          Outcome.Failure(Problem(ProblemCode.STALE_TARGET, message))
        }
      }
    }
  }

  private fun WorkItem.runTarget(): WorkRunTarget? = databaseFileObservation?.let {
    WorkRunTarget(identity.id, identity.repositoryIdentity, Path.of(repositoryRoot), executionId, statusRevision, updatedAt, it)
  }

  private fun workObservation(runtime: RuntimeComponent): WorkStoreObservation =
    existingSnapshot(runtime) { runtime.databaseSession.read { it.workList.observation() } }

  private fun <T : Any> existingSnapshot(runtime: RuntimeComponent, query: () -> T): T =
    runtime.databaseSession.readIfPresent { query() }
      ?: throw databaseMissing(requireNotNull(path).toString(), DatabaseAccessOperation.READ)

  private suspend fun ensureSession(runtime: RuntimeComponent) {
    val selected = requireNotNull(path)
    if (room != null && currentFileMatches() && (observationProblem != null || observer?.isActive == true)) return
    if (fileIdentity != null && !currentFileMatches()) generation++
    disposeSession()
    observationProblem = null
    existingSnapshot(runtime) { Unit }
    fileIdentity = storeFileIdentity(selected)
    val openedRoom = openWorkRoomDatabase(selected)
    val latestMigration =
      try {
        openedRoom.migrations().latest()
      } catch (failure: Throwable) {
        try {
          openedRoom.close()
        } catch (closeFailure: Throwable) {
          failure.addSuppressed(closeFailure)
          RuntimeDiagnostics.record("room_schema_connection_close_failed")
        }
        throw failure
      }
    if (latestMigration?.version != DesktopReadSchema.VERSION) {
      val failure = databaseIncompatible(selected.toString(), DatabaseAccessOperation.READ)
      try {
        openedRoom.close()
      } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
        RuntimeDiagnostics.record("room_schema_connection_close_failed")
      }
      throw failure
    }
    val openedRevisionConnection =
      try {
        ReadOnlyRoomDriver().open(selected.toString())
      } catch (failure: Throwable) {
        try {
          openedRoom.close()
        } catch (closeFailure: Throwable) {
          failure.addSuppressed(closeFailure)
          RuntimeDiagnostics.record("room_schema_connection_close_failed")
        }
        throw failure
      }
    room = openedRoom
    revisionConnection = openedRevisionConnection
    session++
    val ready = CompletableDeferred<Unit>()
    val ownerSession = session
    observer = scope.launch {
      var deliveredRevisions = emptyMap<String, Long>()
      try {
        observeChanges(selected, ready).collect { change ->
          val changedTopics = change.revisions.filter { (topic, revision) -> deliveredRevisions[topic] != revision }.keys
          deliveredRevisions = change.revisions
          if (change.initial) return@collect
          if (changedTopics.isEmpty() && !change.resynchronize) return@collect
          if (changedTopics == setOf(DesktopReadSchema.ACTIVITY_TOPIC) && !change.resynchronize) {
            invalidateDetails()
            return@collect
          }
          val sections = buildSet {
            add(WorkSection.NEEDS_YOU)
            add(WorkSection.HISTORY)
            add(WorkSection.RECENT)
            val topics = if (change.resynchronize) DesktopReadSchema.TOPICS.toSet() else changedTopics
            if (DesktopReadSchema.GOALS_TOPIC in topics) add(WorkSection.GOALS)
            if (DesktopReadSchema.PHASES_TOPIC in topics) add(WorkSection.PHASES)
          }
          if (ownerSession == session) observationProblem = null
          invalidate(sections)
        }
        if (currentFileMatches()) throw IOException("Database change observation ended.")
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (!isObserverFailure(error)) {
          ready.completeExceptionally(error)
          throw error
        }
        val sqliteFailure = generateSequence(error as Throwable) { it.cause }
          .take(MAX_FAILURE_CAUSES).filterIsInstance<androidx.sqlite.SQLiteException>().firstOrNull()
        val providerCode = sqliteFailure?.message?.substringBefore(',')?.removePrefix("Error code: ")?.toIntOrNull()
        RuntimeDiagnostics.recordRequest("observation_unavailable_${error.javaClass.simpleName.lowercase()}",
          providerCode?.let { mapOf("sqlite_error_code" to it) }.orEmpty())
        if (ownerSession != session) return@launch
        observationProblem = Problem(ProblemCode.OBSERVATION_UNAVAILABLE,
          "Database change observation is unavailable. Refresh to reconnect.", StorageFailure.OBSERVATION_UNAVAILABLE)
        invalidate()
        ready.completeExceptionally(ObservationUnavailableException(error))
      }
    }
    try {
      ready.await()
    } catch (error: ObservationUnavailableException) {
      throw error
    } catch (error: Exception) {
      if (isObserverFailure(error)) throw ObservationUnavailableException(error)
      throw error
    }
  }

  private fun invalidate(sections: Set<WorkSection> = WorkSection.entries.toSet()) {
    val detailSections = if (sections == setOf(WorkSection.NEEDS_YOU)) {
      setOf(WorkSection.GOALS, WorkSection.PHASES)
    } else sections
    detailSignals.update { current -> current.mapValues { (section, revision) ->
      if (section in detailSections) revision + 1 else revision
    } }
    mutableRefreshEvents.tryEmit(sections)
    sections.forEach { pagingSources.remove(it)?.second?.invalidate() }
    signals.update { current -> current.mapValues { (section, revision) -> if (section in sections) revision + 1 else revision } }
  }

  private fun invalidateDetails() {
    detailSignals.update { current -> current.mapValues { (_, revision) -> revision + 1 } }
  }

  private fun currentFileMatches(): Boolean = path?.let { Files.exists(it) && fileIdentity == storeFileIdentity(it) } == true

  private suspend fun disposeSession() {
    val teardown =
      SessionTeardown(
        observer = observer,
        room = room,
        revisionConnection = revisionConnection,
        detailOwner = detailOwnerObservation,
        ownerRegistrations = ownerObservations.values.flatMap { it.values },
      )
    generation++
    if (teardown.room != null || teardown.observer != null || teardown.revisionConnection != null) invalidate()
    withContext(Dispatchers.IO + NonCancellable) {
      runCatching { teardown.observer?.cancelAndJoin() }
        .onFailure { RuntimeDiagnostics.record("room_observer_teardown_failed") }
      teardown.detailOwner.closeOwned("room_detail_owner_close_failed")
      teardown.ownerRegistrations.forEach { it.closeOwned("room_owner_registration_close_failed") }
      runCatching { teardown.room?.close() }
        .onFailure { RuntimeDiagnostics.record("room_session_close_failed") }
      runCatching { teardown.revisionConnection?.close() }
        .onFailure { RuntimeDiagnostics.record("room_revision_connection_close_failed") }
      if (observer === teardown.observer) observer = null
      if (room === teardown.room) room = null
      if (revisionConnection === teardown.revisionConnection) revisionConnection = null
      if (detailOwnerObservation === teardown.detailOwner) detailOwnerObservation = null
      ownerObservations.clear()
      deadlines.clear()
      activeRetentions.clear()
      latestQueryEpochs.clear()
      fileIdentity = null
      deadlineRevision.update { it + 1 }
    }
  }

  @Synchronized
  override fun close() {
    if (closed) return
    closed = true
    pagingSources.values.forEach { it.second.invalidate() }
    pagingSources.clear()
    val completion = CompletableDeferred<Unit>()
    closeCompletion = completion
    scope.launch {
      try {
        mutex.withLock { disposeSession() }
      } finally {
        scope.cancel()
        completion.complete(Unit)
      }
    }
  }

  override suspend fun closeAndJoin() {
    close()
    closeCompletion?.await()
  }

  private fun AutoCloseable?.closeOwned(event: String) {
    runCatching { this?.close() }.onFailure { RuntimeDiagnostics.record(event) }
  }

  private suspend fun <T> safeRead(block: suspend () -> Outcome<T>): Outcome<T> = try {
    recordReadWork()
    if (closed) Outcome.Failure(Problem(ProblemCode.DATABASE_ACCESS, "The data connection is closed.", StorageFailure.ACCESS))
    else configurationProblem?.let { Outcome.Failure(it) } ?: block()
  } catch (error: Exception) {
    val causes = generateSequence(error as Throwable?) { it.cause }.take(MAX_FAILURE_CAUSES).toList()
    causes.firstOrNull { it is CancellationException }?.let { throw it }
    causes.filterIsInstance<androidx.sqlite.SQLiteException>().firstOrNull()?.let { providerFailure ->
      val providerCode = providerFailure.message?.substringBefore(',')?.removePrefix("Error code: ")?.toIntOrNull()
      RuntimeDiagnostics.recordRequest("room_read_provider_failure",
        providerCode?.let { mapOf("sqlite_error_code" to it) }.orEmpty())
    }
    val code = causes.firstNotNullOfOrNull { cause ->
      when (cause) {
        is ObservationUnavailableException -> "observation_unavailable"
        is SkillBillRuntimeException -> when (cause.code) {
          DatabaseFailureCode.MISSING -> "missing_database"
          DatabaseFailureCode.BUSY -> "busy"
          DatabaseFailureCode.CORRUPT -> "corrupt_database"
          DatabaseFailureCode.INCOMPATIBLE_SCHEMA -> "incompatible_database"
          DatabaseFailureCode.ACCESS -> "database_access"
          else -> "incompatible_record"
        }
        is androidx.sqlite.SQLiteException -> classifyProviderFailure()
        is IOException -> if (path?.let { Files.notExists(it) } == true) "missing_database" else "database_access"
        is SecurityException -> "database_access"
        else -> null
      }
    } ?: throw error
    failure(code)
  }

  private fun isObserverFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }
    .take(MAX_FAILURE_CAUSES)
    .any { it is IOException || it is androidx.sqlite.SQLiteException || it is SecurityException || it is UnsupportedOperationException }

  private fun classifyProviderFailure(): String {
    if (path?.let { Files.notExists(it) } == true) return "missing_database"
    val runtime = component ?: return "database_access"
    return try {
      existingSnapshot(runtime) { Unit }
      "database_access"
    } catch (failure: SkillBillRuntimeException) {
      when (failure.code) {
        DatabaseFailureCode.MISSING -> "missing_database"
        DatabaseFailureCode.BUSY -> "busy"
        DatabaseFailureCode.CORRUPT -> "corrupt_database"
        DatabaseFailureCode.INCOMPATIBLE_SCHEMA -> "incompatible_database"
        else -> "database_access"
      }
    }
  }

  private fun <T> failure(code: String): Outcome<T> {
    RuntimeDiagnostics.record("work_data_$code")
    val summary = when (code) {
      "missing_database" -> "The selected database does not exist. Choose an existing runtime store."
      "busy" -> "The database is busy. Retry after the writer finishes."
      "corrupt_database" -> "The selected database is corrupt. Restore it through the runtime's supported recovery route."
      "incompatible_record" -> "A saved workflow record could not be read. Check the runtime diagnostics for the affected workflow."
      "incompatible_database" -> "The database is incompatible with this app. Update the runtime and apply its supported migrations before reconnecting."
      "observation_unavailable" -> "Database change observation is unavailable. Reconnect and retry."
      else -> "The selected database could not be read. Check its path and access permissions."
    }
    val problemCode = ProblemCode.fromWire(code)
    return Outcome.Failure(Problem(problemCode, summary, StorageFailure.entries.single { it.code == problemCode.wireValue }))
  }

  private fun <T> staleCursor(): Outcome<T> = Outcome.Failure(Problem(ProblemCode.STALE_TARGET, "The database or query changed. Refresh this view.", StorageFailure.STALE_QUERY))

  private fun belongs(item: WorkItem, section: WorkSection, cutoff: Instant, through: Instant): Boolean = when (section) {
    WorkSection.HISTORY, WorkSection.RECENT -> item.finishedAt?.let { it >= cutoff && it <= through } == true
    WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES -> item.liveSection == section
  }

  private companion object {
    const val PAGE_SIZE = 10
    const val CANDIDATE_PAGE_SIZE = PAGE_SIZE + 1
    const val CANDIDATE_BUDGET = 40
    const val MAX_FAILURE_CAUSES = 16
  }
}

private class ObservationUnavailableException(cause: Throwable) : IOException(cause)
