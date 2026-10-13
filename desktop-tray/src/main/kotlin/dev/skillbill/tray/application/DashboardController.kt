package dev.skillbill.tray.application

import dev.skillbill.tray.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class DashboardController(
  private val boards: WorkBoardRepository,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  private val storeSession: WorkStoreSession,
  private val settingsStore: SettingsStore,
  private val native: NativeServices,
  private val clock: Clock = Clock.systemUTC(),
  private val onSelect: (WorkIdentity) -> Unit,
) : DashboardModel {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutable = MutableStateFlow(DashboardState(AppSettings()))
  override val state: StateFlow<DashboardState> = mutable.asStateFlow()
  private val subscriptions = ConcurrentHashMap<WorkSection, Job>()
  private val pages = ConcurrentHashMap<WorkSection, Int>()
  private val pageWindows = ConcurrentHashMap<WorkSection, List<Board>>()
  private val anchors = ConcurrentHashMap<WorkSection, String>()
  private val sectionGenerations = ConcurrentHashMap<WorkSection, Long>()
  private val locks = WorkSection.entries.associateWith { Mutex() }
  private val canceledItems = mutableMapOf<WorkIdentity, WorkItem>()
  private val controlJobs = mutableMapOf<WorkIdentity, Job>()
  private val appends = ConcurrentHashMap.newKeySet<WorkSection>()
  private val appendJobs = ConcurrentHashMap<WorkSection, Job>()
  private val transitions = TransitionTracker()
  private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
  private val settingsMutex = Mutex()
  private val detailMutex = Mutex()
  private var selectionJob: Job? = null
  private var detailAppendJob: Job? = null
  private val detailWindows = mutableMapOf<DetailList, List<Detail>>()
  @Volatile private var queryGeneration = 0L
  @Volatile private var storeGeneration: Long? = null
  @Volatile private var storeEpoch: Long = 0
  private var switchingStore = false
  private var observationSession: Long? = null
  private val baselineSections = mutableSetOf<WorkSection>()
  private var needsTransitionBaseline = true
  @Volatile private var ready = false

  init {
    scope.launch {
      settingsStore.settings.collect { result ->
        val settings = when (result) {
          is Outcome.Value -> result.value
          is Outcome.Failure -> {
            recordProblem(if (result.problem.code == ProblemCode.SETTINGS_CORRUPT) "settings_corrupt" else "settings_read_failed")
            mutable.update { it.copy(settingsProblem = result.problem, boardLoading = false) }
            return@collect
          }
        }
        settingsMutex.withLock {
          val previous = mutable.value.settings
          val changedPath = !ready || previous.databasePath != settings.databasePath
          val changedRetention = previous.retention != settings.retention
          mutable.update { it.copy(settings = settings, settingsProblem = null) }
          if (!ready || previous.startAtLogin != settings.startAtLogin) {
            if (!native.setStartAtLogin(settings.startAtLogin)) mutable.update { it.copy(settingsProblem = Problem(ProblemCode.LOGIN_SETTING_FAILED, "Start at login could not be changed.")) }
          }
          if (changedPath) {
            val oldJobs = synchronized(this@DashboardController) {
              storeEpoch++
              switchingStore = true
              subscriptions.values.toList() + appendJobs.values + listOfNotNull(selectionJob, detailAppendJob) + controlJobs.values
            }
            oldJobs.forEach(Job::cancel)
            oldJobs.joinAll()
            appendJobs.clear()
            appends.clear()
            storeSession.selectDatabase(settings.databasePath)
            synchronized(this@DashboardController) {
              canceledItems.clear()
              storeGeneration = null
              synchronized(transitions) { transitions.clear() }
              observationSession = null
              baselineSections.clear()
              baselineSections += LIVE_SECTIONS + WorkSection.RECENT
              needsTransitionBaseline = true
              synchronized(detailWindows) { detailWindows.clear() }
              mutable.update { it.copy(board = null, sections = emptyMap(), selected = null, detail = null,
                problem = null, detailProblem = null, actionProblem = null, displayDurations = emptyMap(), boardLoading = true,
                detailLoading = false, detailListLoading = emptySet(), detailExtrasStale = false,
                sectionLoading = OBSERVED_SECTIONS.toSet(),
                sectionLoadStates = emptyMap(), sectionProblems = emptyMap(), stale = false,
                detailLoadStates = emptyMap()) }
            }
            pages.clear()
            pageWindows.clear()
            anchors.clear()
          }
          ready = true
          if (changedRetention) { pages.clear(); pageWindows.clear(); anchors.clear() }
          if (changedPath || changedRetention) restartSubscriptions()
          else if (previous.historyFilter != settings.historyFilter) {
            sectionGenerations.merge(WorkSection.HISTORY, 1L, Long::plus)
            mutable.update { state -> state.copy(sections = state.sections - WorkSection.HISTORY,
              sectionProblems = state.sectionProblems - WorkSection.HISTORY,
              sectionLoading = state.sectionLoading + WorkSection.HISTORY,
              sectionLoadStates = state.sectionLoadStates + (WorkSection.HISTORY to ListLoadState.INITIAL_LOADING)) }
            subscriptions.remove(WorkSection.HISTORY)?.cancelAndJoin()
            appendJobs.remove(WorkSection.HISTORY)?.cancelAndJoin()
            appends.remove(WorkSection.HISTORY)
            pages.remove(WorkSection.HISTORY)
            pageWindows.remove(WorkSection.HISTORY)
            anchors.remove(WorkSection.HISTORY)
            subscribe(WorkSection.HISTORY)
          }
          if (changedPath) synchronized(this@DashboardController) { switchingStore = false }
        }
      }
    }
    scope.launch {
      if (native.hasSystemTray()) trayAvailable() else trayUnavailable()
    }
    scope.launch {
      boards.refreshEvents.collect { sections ->
        mutable.update { state ->
          val sectionStates = state.sectionLoadStates + sections.filter { it in state.sections }
            .associateWith { ListLoadState.REFRESHING }
          val detailStates = if (state.selected != null) state.detailLoadStates.mapValues { (_, loadState) ->
            if (loadState in setOf(ListLoadState.READY, ListLoadState.READY_EMPTY, ListLoadState.EXHAUSTED)) ListLoadState.REFRESHING else loadState
          } else state.detailLoadStates
          state.copy(sectionLoading = state.sectionLoading + sections.filter { it in state.sections }, sectionLoadStates = sectionStates,
            detailLoadStates = detailStates)
        }
      }
    }
    scope.launch {
      for (request in refreshRequests) boards.refresh()
    }
  }

  private fun restartSubscriptions() {
    queryGeneration++
    subscriptions.values.forEach(Job::cancel)
    subscriptions.clear()
    OBSERVED_SECTIONS.forEach(::subscribe)
  }

  private fun subscribe(section: WorkSection) {
    val query = queryGeneration
    val sectionGeneration = sectionGenerations.merge(section, 1L, Long::plus)!!
    val settings = mutable.value.settings
    val filter = if (section == WorkSection.RECENT) "" else mutable.value.filter
    mutable.update { state ->
      val loadState = if (section in state.sections) ListLoadState.REFRESHING else ListLoadState.INITIAL_LOADING
      state.copy(sectionLoading = state.sectionLoading + section,
        sectionLoadStates = state.sectionLoadStates + (section to loadState))
    }
    subscriptions[section] = scope.launch {
      boards.observe(section, settings.retention, filter, if (section == WorkSection.HISTORY) settings.historyFilter else HistoryFilter.ALL, anchors[section]).collect { first ->
        locks.getValue(section).withLock {
          if (query != queryGeneration || sectionGeneration != sectionGenerations[section]) return@withLock
          when (first) {
            is Outcome.Failure -> sectionFailure(section, first.problem)
            is Outcome.Value -> {
              if (first.value.finishedWithin != settings.retention || first.value.filter != filter ||
                first.value.historyFilter != (if (section == WorkSection.HISTORY) settings.historyFilter else HistoryFilter.ALL) ||
                first.value.queryEpoch <= 0 || first.value.storeId.isNullOrBlank() || first.value.revision == null) {
                refreshAfterPageMismatch(section)
                return@withLock
              }
              var board = first.value
              val window = mutableListOf(first.value)
              val cursors = mutableSetOf<String>()
              repeat((pages[section] ?: 1) - 1) {
                val cursor = board.nextCursor ?: return@repeat
                if (!cursors.add(cursor)) { sectionFailure(section, Problem(ProblemCode.STALE_TARGET, "This page changed. Retry.")); return@withLock }
                when (val next = boards.board(section, settings.retention, cursor, filter, if (section == WorkSection.HISTORY) settings.historyFilter else HistoryFilter.ALL)) {
                  is Outcome.Failure -> { sectionFailure(section, next.problem); return@withLock }
                  is Outcome.Value -> {
                    if (!sameObservation(first.value, next.value)) {
                      refreshAfterPageMismatch(section)
                      return@withLock
                    }
                    window += next.value
                    board = next.value.copy(items = (board.items + next.value.items).distinctBy { it.identity })
                  }
                }
              }
              if (query == queryGeneration && sectionGeneration == sectionGenerations[section]) {
                pageWindows[section] = window
                retentionFor(section, window)?.let(boards::retainPages)
                publish(section, board)
              }
            }
          }
        }
      }
    }
  }

  private fun sameObservation(left: Board, right: Board): Boolean =
    left.generation == right.generation &&
      left.observationSession == right.observationSession &&
      left.storeId == right.storeId &&
      left.revision == right.revision &&
      left.queryEpoch == right.queryEpoch &&
      left.finishedWithin == right.finishedWithin &&
      left.filter == right.filter &&
      left.historyFilter == right.historyFilter

  private fun retentionFor(section: WorkSection, window: List<Board>): BoardRetention? {
    val first = window.firstOrNull() ?: return null
    if (window.any { !sameObservation(first, it) }) return null
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
      pageKeys = window.map(Board::pageKey),
    )
  }

  private fun refreshAfterPageMismatch(section: WorkSection) {
    sectionFailure(section, Problem(ProblemCode.STALE_TARGET, "The page changed while this list was loading."))
    refreshRequests.trySend(Unit)
  }

  @Synchronized
  private fun publish(section: WorkSection, board: Board) {
    if (observationSession != board.observationSession) {
      observationSession = board.observationSession
      baselineSections.clear()
      baselineSections += LIVE_SECTIONS + WorkSection.RECENT
      needsTransitionBaseline = true
    }
    if (storeGeneration != null && storeGeneration != board.generation) {
      storeEpoch++
      canceledItems.clear()
      controlJobs.values.forEach(Job::cancel)
      synchronized(transitions) { transitions.clear() }
      baselineSections.clear()
      baselineSections += LIVE_SECTIONS + WorkSection.RECENT
      needsTransitionBaseline = true
      selectionJob?.cancel()
      detailAppendJob?.cancel()
      pages.clear()
      pageWindows.clear()
      anchors.clear()
      synchronized(detailWindows) { detailWindows.clear() }
      storeGeneration = board.generation
      restartSubscriptions()
      mutable.update { it.copy(sections = emptyMap(), board = null, boardLoading = true,
        sectionLoading = OBSERVED_SECTIONS.toSet(),
        selected = null, detail = null, detailLoading = false, detailListLoading = emptySet(), detailExtrasStale = false,
        displayDurations = emptyMap(), actionProblem = null, sectionLoadStates = emptyMap(), detailLoadStates = emptyMap(),
        sectionProblems = emptyMap(), problem = null, stale = false, detailProblem = null) }
    }
    storeGeneration = board.generation
    mutable.update { state ->
      val incoming = board.items.map { item ->
        val canceled = canceledItems[item.identity]
        if (canceled != null && item.updatedAt <= canceled.updatedAt) canceled else {
          canceledItems.remove(item.identity)
          item
        }
      }
      val reconciled = board.copy(items = incoming.filter { section !in LIVE_SECTIONS || it.lifecycle != Lifecycle.CANCELED })
      val sections = state.sections + (section to reconciled)
      val live = LIVE_SECTIONS.flatMap { sections[it]?.items.orEmpty() }.distinctBy { it.identity }
      val recent = sections[WorkSection.RECENT]?.items.orEmpty()
      val loading = state.sectionLoading - section
      state.copy(sections = sections, databasePath = board.databasePath, board = board.copy(items = (live + recent).distinctBy { it.identity }, nextCursor = null),
        sectionLoading = loading, boardLoading = LIVE_SECTIONS.any { it in loading && it !in sections },
        sectionProblems = state.sectionProblems - section,
        sectionLoadStates = state.sectionLoadStates + (section to when {
          board.items.isEmpty() && board.nextCursor == null -> ListLoadState.READY_EMPTY
          board.nextCursor == null -> ListLoadState.EXHAUSTED
          else -> ListLoadState.READY
        }),
        problem = (state.sectionProblems - section).filterKeys { it in LIVE_SECTIONS }.values.firstOrNull(),
        stale = (state.sectionProblems - section).keys.any { it in LIVE_SECTIONS },
        displayDurations = live.associate { item -> item.identity to DisplayDurations(item.activeDurationMs, item.currentSubtaskId, item.subtaskActiveDurationMs) })
    }
    selectDefaultItem()
    baselineSections.remove(section)
    if (section != WorkSection.HISTORY && baselineSections.isEmpty()) synchronized(transitions) {
      mutable.value.board?.let { combined ->
        val events = if (needsTransitionBaseline) {
          transitions.baseline(combined)
          needsTransitionBaseline = false
          emptyList()
        } else transitions.observe(combined)
        val notificationEpoch = storeEpoch
        events.forEach { (item, transition) ->
          if (mutable.value.settings.notifications[transition] == true) native.notifyAuthorized(item.summary, item.currentStepLabel,
            item.identity, { send ->
              if (notificationEpoch == storeEpoch && mutable.value.settings.notifications[transition] == true) send()
            }, ::select)
        }

      }
    }
  }

  private fun selectDefaultItem() {
    val state = mutable.value
    if (state.selected != null || LIVE_SECTIONS.any {
      it !in state.sections || it in state.sectionProblems
    }) return
    val live = LIVE_SECTIONS.firstNotNullOfOrNull { state.sections[it]?.items?.firstOrNull() }
    if (live != null) {
      selectItem(live.identity)
      return
    }
    if (LIVE_SECTIONS.any { state.sections[it]?.nextCursor != null } ||
      WorkSection.HISTORY in state.sectionLoading || WorkSection.HISTORY in state.sectionProblems) return
    state.sections[WorkSection.HISTORY]?.items?.firstOrNull()?.let { selectItem(it.identity) }
  }

  private fun sectionFailure(section: WorkSection, problem: Problem, append: Boolean = false) {
    recordProblem("section_read_${section.name.lowercase()}_${problem.code.wireValue}")
    mutable.update { state ->
      state.copy(sectionProblems = state.sectionProblems + (section to problem), sectionLoading = state.sectionLoading - section,
        sectionLoadStates = state.sectionLoadStates + (section to if (append) ListLoadState.APPEND_FAILED else ListLoadState.FAILED),
        boardLoading = LIVE_SECTIONS.any { it in state.sectionLoading - section && it !in state.sections },
        problem = if (section in LIVE_SECTIONS) problem else state.problem,
        stale = state.board != null && section in LIVE_SECTIONS || state.stale)
    }
  }

  override fun refresh() {
    mutable.update { state ->
      state.copy(
        sectionLoading = state.sectionLoading + state.sections.keys,
        sectionLoadStates = state.sectionLoadStates + state.sections.keys.associateWith { ListLoadState.REFRESHING },
        detailLoadStates = state.detailLoadStates.mapValues { (_, loadState) ->
          if (loadState in setOf(ListLoadState.READY, ListLoadState.READY_EMPTY, ListLoadState.EXHAUSTED)) ListLoadState.REFRESHING else loadState
        },
      )
    }
    refreshRequests.trySend(Unit)
  }

  override fun loadMoreBoard() { LIVE_SECTIONS.forEach(::loadMore) }

  override fun loadMore(section: WorkSection) {
    val state = mutable.value
    val board = state.sections[section] ?: return
    if (board.nextCursor == null || section in state.sectionLoading ||
      board.finishedWithin != state.settings.retention ||
      section == WorkSection.HISTORY && board.historyFilter != state.settings.historyFilter) return
    val lock = locks.getValue(section)
    if (!appends.add(section)) return
    val query = queryGeneration
    val epoch = storeEpoch
    val sectionGeneration = sectionGenerations[section]
    mutable.update { it.copy(sectionLoading = it.sectionLoading + section,
      sectionLoadStates = it.sectionLoadStates + (section to ListLoadState.APPENDING)) }
    val appendJob = scope.launch(start = CoroutineStart.LAZY) {
      try {
        lock.withLock {
        if (epoch != storeEpoch || query != queryGeneration || sectionGeneration != sectionGenerations[section]) return@withLock
        val base = mutable.value.sections[section] ?: return@withLock
        val cursor = base.nextCursor ?: return@withLock
        val settings = mutable.value.settings
        val filter = if (section == WorkSection.RECENT) "" else mutable.value.filter
        val historyFilter = if (section == WorkSection.HISTORY) settings.historyFilter else HistoryFilter.ALL
        val result = boards.board(section, settings.retention, cursor, filter, historyFilter)
        if (epoch != storeEpoch || query != queryGeneration || sectionGeneration != sectionGenerations[section]) return@withLock
        when (result) {
          is Outcome.Failure -> sectionFailure(section, result.problem, append = true)
          is Outcome.Value -> if (epoch == storeEpoch && query == queryGeneration && sameObservation(base, result.value)) {
            val window = (pageWindows[section].orEmpty() + result.value).toMutableList()
            var shifted = false
            if (window.size > MAX_PAGES) {
              val removed = window.removeAt(0)
              removed.nextCursor?.let { anchors[section] = it }
              shifted = true
            }
            pageWindows[section] = window
            pages[section] = window.size
            retentionFor(section, window)?.let(boards::retainPages)
            publish(section, result.value.copy(items = window.flatMap { it.items }.distinctBy { it.identity }))
            if (shifted) {
              subscriptions.remove(section)?.cancel()
              subscribe(section)
            }
          } else {
            sectionFailure(section, Problem(ProblemCode.STALE_TARGET, "The page changed while this list was loading."), append = true)
            refreshRequests.trySend(Unit)
          }
        }
        }
      } finally {
        appends.remove(section)
        appendJobs.remove(section, currentCoroutineContext().job)
      }
    }
    appendJobs[section] = appendJob
    appendJob.start()
  }

  override fun filter(value: String) {
    if (mutable.value.filter == value) return
    mutable.update { it.copy(filter = value) }
    pages.clear()
    pageWindows.clear()
    anchors.clear()
    if (ready) restartSubscriptions()
  }

  override fun select(identity: WorkIdentity) {
    onSelect(identity)
    selectItem(identity)
  }

  @Synchronized
  private fun selectItem(identity: WorkIdentity) {
    synchronized(detailWindows) { detailWindows.clear() }
    mutable.update { it.copy(selected = identity, detail = null, detailProblem = null, detailLoading = true,
      detailLoadStates = DetailList.entries.associateWith { ListLoadState.INITIAL_LOADING }) }
    detailAppendJob?.cancel()
    observeSelectedDetail(identity)
  }

  private fun refreshDetail(append: DetailList? = null) {
    val list = append ?: return
    val identity = mutable.value.selected ?: return
    val query = queryGeneration
    val generation = storeGeneration
    detailAppendJob = scope.launch {
      detailMutex.withLock {
        val previous = mutable.value.detail
        mutable.update { it.copy(detailListLoading = setOf(list),
          detailLoadStates = it.detailLoadStates + (list to ListLoadState.APPENDING)) }
        val windows = synchronized(detailWindows) { detailWindows.toMutableMap() }
        val cursor = previous?.cursors?.get(list) ?: run {
          mutable.update { it.copy(detailLoadStates = it.detailLoadStates + (list to ListLoadState.EXHAUSTED), detailListLoading = emptySet()) }
          return@withLock
        }
        var result = details.detail(identity, cursor, list)
        if (result is Outcome.Value && previous != null) {
          if (result.value.item.databaseStoreId != previous.item.databaseStoreId ||
            result.value.item.databaseRevision != previous.item.databaseRevision) {
            result = Outcome.Failure(Problem(ProblemCode.STALE_TARGET, "Detail changed during append. Refresh before retrying."))
          } else {
            windows[list] = (windows[list].orEmpty() + result.value).takeLast(MAX_PAGES)
            result = Outcome.Value(replaceDetailList(previous, list, windows.getValue(list)))
          }
        }
        if (query != queryGeneration || generation != storeGeneration || mutable.value.selected != identity) return@launch
        when (val page = result) {
          is Outcome.Value -> {
            synchronized(detailWindows) { detailWindows.clear(); detailWindows.putAll(windows) }
            mutable.update { it.copy(detail = page.value, detailProblem = null, detailLoading = false,
              detailListLoading = emptySet(), detailExtrasStale = false,
              detailLoadStates = it.detailLoadStates + (list to stateFor(page.value, list))) }
          }
          is Outcome.Failure -> mutable.update { it.copy(detailProblem = page.problem, detailLoading = false,
            detailListLoading = emptySet(), detailExtrasStale = previous != null,
            detailLoadStates = it.detailLoadStates + (list to ListLoadState.APPEND_FAILED)) }
        }
      }
    }
  }

  private fun observeSelectedDetail(identity: WorkIdentity) {
    val previousSelection = selectionJob
    previousSelection?.cancel()
    val epoch = storeEpoch
    selectionJob = scope.launch {
      previousSelection?.let { previous -> withContext(NonCancellable) { previous.join() } }
      currentCoroutineContext().ensureActive()
      details.observeDetail(identity).collect { observed ->
        detailMutex.withLock {
          if (epoch != storeEpoch || mutable.value.selected != identity) return@withLock
          var result = observed
          val windows = synchronized(detailWindows) { detailWindows.toMap() }
          val refreshed = mutableMapOf<DetailList, List<Detail>>()
          if (observed is Outcome.Value) {
            var combined = observed.value
            for (list in DetailList.entries) {
              val retained = windows[list].orEmpty()
              val start = retained.firstOrNull()?.pageOffset ?: 0
              var page = if (start == 0) observed else details.detail(identity, list = list, startOffset = start)
              val replacement = mutableListOf<Detail>()
              val cursors = mutableSetOf<String>()
              while (replacement.size < retained.size.coerceAtLeast(1)) {
                when (val value = page) {
                  is Outcome.Failure -> { result = value; break }
                  is Outcome.Value -> {
                    if (value.value.item.databaseStoreId != observed.value.item.databaseStoreId ||
                      value.value.item.databaseRevision != observed.value.item.databaseRevision) {
                      result = Outcome.Failure(Problem(ProblemCode.STALE_TARGET, "Detail changed during refresh. Retry."))
                      break
                    }
                    replacement += value.value
                    val cursor = value.value.cursors[list]
                    if (replacement.size < retained.size && cursor != null && cursors.add(cursor)) {
                      page = details.detail(identity, cursor, list)
                    } else break
                  }
                }
              }
              if (result is Outcome.Failure) break
              refreshed[list] = replacement
              combined = replaceDetailList(combined, list, replacement)
            }
            if (result !is Outcome.Failure) result = Outcome.Value(combined)
          }
          if (epoch != storeEpoch || mutable.value.selected != identity) return@withLock
          when (val settled = result) {
            is Outcome.Failure -> mutable.update { state -> state.copy(detailProblem = settled.problem,
              detailLoading = false, detailExtrasStale = state.detail != null,
              detailLoadStates = state.detailLoadStates.mapValues { (_, loadState) ->
                if (loadState == ListLoadState.INITIAL_LOADING) ListLoadState.FAILED else loadState
              }) }
            is Outcome.Value -> {
              synchronized(detailWindows) { detailWindows.clear(); detailWindows.putAll(refreshed) }
              mutable.update { state -> state.copy(detail = settled.value, detailProblem = null, detailLoading = false,
                detailListLoading = emptySet(), detailExtrasStale = false,
                detailLoadStates = DetailList.entries.associateWith { list -> stateFor(settled.value, list) }) }
            }
          }
        }
      }
    }
  }

  private fun stateFor(detail: Detail, list: DetailList): ListLoadState {
    val isEmpty = when (list) {
      DetailList.SUBTASKS -> detail.subtasks.isEmpty()
      DetailList.PHASES -> detail.phases.isEmpty()
      DetailList.VALIDATION -> detail.validation.isEmpty()
      DetailList.EXECUTIONS -> detail.executionHistory.isEmpty()
      DetailList.DEPENDENCIES -> detail.dependencies.isEmpty()
    }
    return when {
      isEmpty && detail.cursors[list] == null -> ListLoadState.READY_EMPTY
      detail.cursors[list] == null -> ListLoadState.EXHAUSTED
      else -> ListLoadState.READY
    }
  }

  override fun loadMoreDetail(list: DetailList) {
    if (detailAppendJob?.isActive == true || mutable.value.detail?.cursors?.get(list) == null) return
    refreshDetail(list)
  }

  private fun replaceDetailList(base: Detail, list: DetailList, window: List<Detail>): Detail {
    val cursors = base.cursors - list + window.lastOrNull()?.cursors.orEmpty().filterKeys { it == list }
    return when (list) {
      DetailList.SUBTASKS -> base.copy(subtasks = window.flatMap { it.subtasks }.distinctBy { it.id }, cursors = cursors)
      DetailList.PHASES -> base.copy(phases = window.flatMap { it.phases }.distinctBy { it.id }, cursors = cursors)
      DetailList.VALIDATION -> base.copy(validation = window.flatMap { it.validation }.distinctBy { it.subtaskId }, cursors = cursors)
      DetailList.DEPENDENCIES -> base.copy(dependencies = window.flatMap { it.dependencies }.distinctBy { it.subtaskId to it.dependency.subtaskId }, cursors = cursors)
      DetailList.EXECUTIONS -> base.copy(executionHistory = window.flatMap { it.executionHistory }.distinctBy { it.invocationId }, cursors = cursors)
    }
  }

  override fun updateSettings(settings: AppSettings) {
    val previous = mutable.value.settings
    scope.launch {
      val result = settingsStore.update { current ->
        current.copy(
          databasePath = if (settings.databasePath != previous.databasePath) settings.databasePath else current.databasePath,
          retention = if (settings.retention != previous.retention) settings.retention else current.retention,
          historyFilter = if (settings.historyFilter != previous.historyFilter) settings.historyFilter else current.historyFilter,
          startAtLogin = if (settings.startAtLogin != previous.startAtLogin) settings.startAtLogin else current.startAtLogin,
          lastPopoverTab = if (settings.lastPopoverTab != previous.lastPopoverTab) settings.lastPopoverTab else current.lastPopoverTab,
          notifications = current.notifications + settings.notifications.filter { (key, value) -> previous.notifications[key] != value },
        )
      }
      if (result is Outcome.Failure) {
        recordProblem(if (result.problem.code == ProblemCode.SETTINGS_CORRUPT) "settings_corrupt" else "settings_write_failed")
        mutable.update { it.copy(settingsProblem = result.problem) }
      }
    }
  }

  override fun chooseTab(tab: String) {
    if (tab in setOf("Goals", "Phases")) updateSettings(mutable.value.settings.copy(lastPopoverTab = tab))
  }

  override fun trayUnavailable() { mutable.update { it.copy(trayProbeComplete = true, trayProblem = Problem(ProblemCode.TRAY_UNAVAILABLE, "The system tray is unavailable. Skill Bill remains open in its desktop window.")) } }
  override fun trayAvailable() { mutable.update { it.copy(trayProbeComplete = true, trayProblem = null) } }

  override fun perform(action: String, identity: WorkIdentity) {
    val actionName = action.substringBefore(':')
    if (actionName !in setOf("pause", "stop", "cancel", "launch")) return
    val agentId = action.substringAfter(':', "").takeIf(String::isNotBlank)
    val job = synchronized(this) {
      val current = mutable.value
      val boardItem = current.board?.items?.singleOrNull { it.identity == identity }
        ?: current.detail?.item?.takeIf { it.identity == identity } ?: return
      val selectedDetail = current.detail?.takeIf { it.item.identity == identity }
      val item = if (actionName in setOf("cancel", "launch")) selectedDetail?.item ?: return else boardItem
      val generation = storeGeneration ?: return
      val options = selectedDetail?.runOptions
      val eligible = when (actionName) {
        "cancel" -> options?.canCancel == true
        "launch" -> options?.canLaunch == true
        else -> item.freshness == Freshness.FRESH && item.eligibleForControl() &&
          !(actionName == "pause" && item.pauseRequested)
      }
      if (switchingStore || identity in controlJobs || current.stale || !eligible || item.databaseGeneration != generation) return
      val epoch = storeEpoch
      val knownAttempts = current.board?.items.orEmpty().map { it.identity }.toSet()
      val launchRequestedAt = clock.instant()
      val controlJob = scope.launch(start = CoroutineStart.LAZY) {
        val owner = currentCoroutineContext().job
        try {
          if (!isCurrentControl(identity, owner, epoch, generation)) return@launch
          val result = when (actionName) {
            "pause" -> controls.pause(item)
            "cancel" -> controls.cancel(item)
            "launch" -> controls.launch(item, agentId)
            else -> controls.stop(item)
          }
          val published = synchronized(this@DashboardController) {
            if (!isCurrentControlLocked(identity, owner, epoch, generation)) false
            else {
              mutable.update { it.copy(actionProblem = (result as? Outcome.Failure)?.problem) }
              true
            }
          }
          if (!published || !isCurrentControl(identity, owner, epoch, generation)) return@launch
          if (result is Outcome.Value) {
            if (actionName == "cancel") confirmCancellation(item)
            if (actionName == "launch") synchronized(this@DashboardController) { canceledItems.remove(identity) }
          }
          boards.refresh()
          if (result is Outcome.Value && actionName == "launch") followLaunch(item, knownAttempts, launchRequestedAt)
        } finally {
          synchronized(this@DashboardController) {
            if (controlJobs[identity] === owner) {
              controlJobs.remove(identity)
              mutable.update { it.copy(actionsInProgress = it.actionsInProgress - identity) }
            }
          }
        }
      }
      mutable.update { it.copy(actionsInProgress = it.actionsInProgress + identity, actionProblem = null) }
      controlJobs[identity] = controlJob
      controlJob.invokeOnCompletion {
        synchronized(this@DashboardController) {
          if (controlJobs[identity] === controlJob) {
            controlJobs.remove(identity)
            mutable.update { it.copy(actionsInProgress = it.actionsInProgress - identity) }
          }
        }
      }
      controlJob
    }
    job.start()
  }

  private suspend fun confirmCancellation(item: WorkItem) {
    val canceled = item.copy(lifecycle = Lifecycle.CANCELED, finishedAt = clock.instant(), updatedAt = clock.instant(),
      requiresAttention = false, liveSection = null, controlEligible = false)
    synchronized(this) {
      canceledItems[item.identity] = canceled
      mutable.update { state ->
        val sections = state.sections.mapValues { (section, board) ->
          board.copy(items = board.items.map { if (it.identity == item.identity) canceled else it }
            .filter { section !in LIVE_SECTIONS || it.identity != item.identity })
        }
        state.copy(sections = sections, board = state.board?.copy(items = state.board.items.map {
          if (it.identity == item.identity) canceled else it
        }))
      }
    }
    val confirmed = details.detail(item.identity)
    if (confirmed is Outcome.Value) synchronized(this) {
      if (mutable.value.selected == item.identity) mutable.update { it.copy(detail = confirmed.value) }
    }
  }

  private suspend fun followLaunch(item: WorkItem, knownAttempts: Set<WorkIdentity>, requestedAt: Instant) {
    val successor = withTimeoutOrNull(120_000) {
      state.map { current -> current.board?.items?.firstOrNull { candidate ->
        candidate.identity.repositoryIdentity == item.identity.repositoryIdentity && candidate.branch == item.branch &&
          candidate.phaseId == item.phaseId && candidate.issueKey == item.issueKey &&
          if (item.scope == ExecutionScope.STANDALONE_PHASE) {
            candidate.identity !in knownAttempts && candidate.startedAt?.let { started ->
              started >= requestedAt && (item.startedAt?.let { started > it } ?: (candidate.updatedAt > item.updatedAt))
            } == true
          } else {
            candidate.identity == item.identity && candidate.updatedAt > item.updatedAt &&
              (candidate.executionId != item.executionId || candidate.lifecycle in setOf(Lifecycle.ACTIVE, Lifecycle.TERMINAL))
          }
      } }.filterNotNull().first()
    }
    if (successor != null) {
      if (mutable.value.selected == item.identity) selectItem(successor.identity)
    } else {
      recordProblem("launch_registration_timeout")
      mutable.update { it.copy(actionProblem = Problem(ProblemCode.UNKNOWN,
        "The harness started but has not registered a new attempt yet. Check its output before trying again.")) }
    }
  }

  private fun isCurrentControl(identity: WorkIdentity, job: Job, epoch: Long, generation: Long): Boolean =
    synchronized(this) { isCurrentControlLocked(identity, job, epoch, generation) }

  private fun isCurrentControlLocked(identity: WorkIdentity, job: Job, epoch: Long, generation: Long): Boolean =
    !switchingStore && job.isActive && controlJobs[identity] === job && storeEpoch == epoch && storeGeneration == generation

  override fun close() {
    scope.cancel()
    storeSession.close()
    settingsStore.close()
  }

  suspend fun closeAndJoin() = withContext(NonCancellable + Dispatchers.IO) {
    scope.coroutineContext[Job]?.cancelAndJoin()
    try {
      storeSession.closeAndJoin()
    } finally {
      settingsStore.closeAndJoin()
    }
  }

  private companion object {
    val LIVE_SECTIONS = listOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES)
    val OBSERVED_SECTIONS = LIVE_SECTIONS + WorkSection.RECENT + WorkSection.HISTORY
    const val MAX_PAGES = 5
    const val PAGE_SIZE = 10
  }
}

fun enabledTransitions(events: List<Pair<WorkItem, String>>, settings: AppSettings): List<Pair<WorkItem, String>> =
  events.filter { (_, transition) -> transition in allowedTransitions && settings.notifications[transition] == true }

class TransitionTracker {
  private data class Key(val identity: WorkIdentity, val executionId: String?)
  private data class Observation(val lifecycle: Lifecycle, val revision: String?, val updatedAt: Instant,
    val storeId: String?, val storeRevision: Long?)
  private val last = linkedMapOf<Key, Observation>()

  fun baseline(board: Board) {
    last.clear()
    board.items.filter { it.freshness == Freshness.FRESH }.forEach { item ->
      val key = Key(item.identity, item.executionId ?: item.invocationId)
      last.remove(key)
      last[key] = observation(item)
      while (last.size > 1000) last.remove(last.keys.first())
    }
  }

  fun observe(board: Board): List<Pair<WorkItem, String>> = buildList {
    board.items.forEach { item ->
      if (item.freshness != Freshness.FRESH) return@forEach
      val key = Key(item.identity, item.executionId ?: item.invocationId)
      val prior = last[key]
      if (prior != null && !isNewer(item, prior)) return@forEach
      last.remove(key)
      last[key] = observation(item)
      while (last.size > 1000) last.remove(last.keys.first())
      if (prior == null) return@forEach
      val event = when {
        item.lifecycle == Lifecycle.BLOCKED && prior.lifecycle != Lifecycle.BLOCKED -> "blocked"
        item.lifecycle == Lifecycle.FAILED && prior.lifecycle != Lifecycle.FAILED -> "failed"
        item.family == WorkFamily.FEATURE_GOAL && item.lifecycle == Lifecycle.TERMINAL && prior.lifecycle != Lifecycle.TERMINAL -> "goal_completed"
        item.lifecycle == Lifecycle.PAUSED && prior.lifecycle != Lifecycle.PAUSED -> "pause_took_effect"
        item.scope == ExecutionScope.STANDALONE_PHASE && item.lifecycle == Lifecycle.TERMINAL && prior.lifecycle != Lifecycle.TERMINAL -> "standalone_phase_finished"
        else -> null
      }
      if (event != null) add(item to event)
    }
  }

  fun clear() { last.clear() }

  private fun observation(item: WorkItem) = Observation(item.lifecycle, item.statusRevision, item.updatedAt,
    item.databaseStoreId, item.databaseRevision)

  private fun isNewer(item: WorkItem, prior: Observation): Boolean {
    if (item.databaseStoreId != prior.storeId) return false
    if (item.databaseRevision != null && prior.storeRevision != null && item.databaseRevision != prior.storeRevision) {
      return item.databaseRevision > prior.storeRevision
    }
    val revision = item.statusRevision
    val previousRevision = prior.revision
    if (revision != null && previousRevision != null) {
      val normalized = revision.trimStart('0').ifEmpty { "0" }
      val previous = previousRevision.trimStart('0').ifEmpty { "0" }
      if (normalized.length != previous.length) return normalized.length > previous.length
      if (normalized != previous) return normalized > previous
    }
    return item.updatedAt.isAfter(prior.updatedAt)
  }
}

data class BoardProjection(
  val running: Int,
  val needsYou: Int,
  val done24h: Int,
  val goals: List<WorkItem>,
  val phases: List<WorkItem>,
  val livePhases: List<WorkItem> = phases,
  val finishedPhases: List<WorkItem> = emptyList(),
)

fun project(board: Board, now: java.time.Instant): BoardProjection {
  val goals = board.items.filter { it.displayGroup == WorkSection.GOALS && it.liveSection != null }.sortedWith(
    compareBy<WorkItem> { it.displayPriority }.thenBy { it.identity.id }.thenBy { it.identity.repositoryIdentity }
  )
  val phaseItems = board.items.filter { it.isPhase() }
  val finished = phaseItems.filter { it.finishedAt?.let { end -> !end.isBefore(now.minus(Duration.ofHours(1))) && !end.isAfter(now) } == true }
  val live = phaseItems.filter { it.liveSection != null }
  return BoardProjection(board.items.count { it.liveSection in setOf(WorkSection.GOALS, WorkSection.PHASES) }, board.items.count {
    it.needsAttention()
  }, board.items.count { it.lifecycle == Lifecycle.TERMINAL && it.finishedAt?.let { done -> !done.isBefore(board.observedAt.minus(Duration.ofHours(24))) && !done.isAfter(board.observedAt) } == true }, goals, live + finished, live, finished)
}

fun displayedDuration(baseMs: Long?, asOf: java.time.Instant?, now: java.time.Instant, freshness: Freshness, live: Boolean): Long? {
  if (baseMs == null) return null
  if (!live || freshness != Freshness.FRESH || asOf == null) return baseMs
  return (baseMs + Duration.between(asOf, now).toMillis().coerceAtLeast(0)).coerceAtLeast(baseMs)
}

private fun recordProblem(category: String) { System.getLogger("dev.skillbill.tray").log(System.Logger.Level.WARNING, "desktop:$category") }
