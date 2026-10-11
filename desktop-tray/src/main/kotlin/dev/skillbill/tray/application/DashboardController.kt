package dev.skillbill.tray.application

import dev.skillbill.tray.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class DashboardController(
  private val source: BoardSource,
  private val settingsStore: SettingsStore,
  private val native: NativeServices,
  private val onSelect: (WorkIdentity) -> Unit,
) : DashboardModel {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val initialSettings = loadInitialSettings()
  private val mutable = MutableStateFlow(DashboardState(settings = initialSettings.first, settingsProblem = initialSettings.second))
  override val state: StateFlow<DashboardState> = mutable.asStateFlow()
  private val pollInputsLock = Any()
  private val settingsGeneration = AtomicLong()
  private val selectionGeneration = AtomicLong()
  private val busyActions = ConcurrentHashMap.newKeySet<String>()
  private val transitions = TransitionTracker()
  private val announced = LinkedHashSet<String>()
  private val notificationLock = Any()
  private val detailRefreshLock = Any()
  private var detailRefreshJob: Job? = null
  private var pendingDetailRefresh: Pair<WorkIdentity, Long>? = null
  private val boardPageMutex = Mutex()
  private val detailPageMutex = Mutex()
  private var loadedBoardPages = 1
  private var loadedDetailPages = 1
  @Volatile private var boardRevision = 0L

  init {
    source.setExecutableOverride(mutable.value.settings.cliOverride)
    if (!native.setStartAtLogin(mutable.value.settings.startAtLogin)) {
      System.getLogger("dev.skillbill.tray").log(System.Logger.Level.WARNING, "login_start_initialization_failed")
      mutable.update { it.copy(settingsProblem = Problem("login_setting_failed", "Start at login could not be enabled.")) }
    }
    scope.launch {
      val available = native.hasSystemTray()
      if (!available) mutable.update { it.copy(trayProbeComplete = true, trayProblem = Problem("tray_unavailable", "The system tray is unavailable. Skill Bill remains open in its desktop window.")) }
      else mutable.update { it.copy(trayProbeComplete = true) }
    }
    scope.launch { pollLoop() }
  }

  override fun refresh() {
    mutable.value.selected?.let { enqueueDetailRefresh(it, boardRevision) }
    scope.launch { source.refresh() }
  }

  override fun loadMoreBoard() {
    synchronized(pollInputsLock) {
      val state = mutable.value
      val cursor = state.board?.nextCursor ?: return
      if (state.boardLoading || state.stale) return
      mutable.update { it.copy(boardLoading = true) }
      val generation = settingsGeneration.get()
      scope.launch {
        boardPageMutex.withLock {
          if (generation == settingsGeneration.get() && mutable.value.board?.nextCursor == cursor) {
            loadBoardPages(state.settings, generation, cursor)
          }
        }
      }
    }
  }

  override fun loadMoreDetail() {
    synchronized(detailRefreshLock) {
      val state = mutable.value
      val identity = state.selected ?: return
      val cursor = state.detail?.nextCursor ?: return
      if (state.detailLoading || state.stale) return
      mutable.update { it.copy(detailLoading = true) }
      val selection = selectionGeneration.get()
      val settings = settingsGeneration.get()
      scope.launch {
        detailPageMutex.withLock {
          if (selection == selectionGeneration.get() && settings == settingsGeneration.get() && mutable.value.detail?.nextCursor == cursor) {
            loadDetailPages(identity, boardRevision, selection, settings, cursor)
          }
        }
      }
    }
  }

  override fun select(identity: WorkIdentity) {
    onSelect(identity)
    selectionGeneration.incrementAndGet()
    loadedDetailPages = 1
    mutable.update { it.copy(selected = identity, detail = null, detailProblem = null, detailLoading = true) }
    enqueueDetailRefresh(identity, boardRevision)
  }

  override fun updateSettings(settings: AppSettings) {
    val previous = mutable.value.settings
    try {
      settingsStore.save(settings)
    } catch (_: Exception) {
      mutable.update { it.copy(settingsProblem = Problem("settings_unavailable", "Settings could not be saved.")) }
      return
    }
    val changedPollInputs = previous.retention != settings.retention || previous.cliOverride != settings.cliOverride
    synchronized(pollInputsLock) {
      if (changedPollInputs) { settingsGeneration.incrementAndGet(); loadedBoardPages = 1; loadedDetailPages = 1 }
      synchronized(notificationLock) { mutable.update { it.copy(settings = settings, settingsProblem = null) } }
      source.setExecutableOverride(settings.cliOverride)
      if (changedPollInputs) {
        source.invalidateBoard()
        mutable.update { it.copy(stale = it.board != null, detailExtrasStale = it.selected != null) }
      }
    }
    if (previous.startAtLogin != settings.startAtLogin && !native.setStartAtLogin(settings.startAtLogin)) {
      try {
        settingsStore.save(settings.copy(startAtLogin = previous.startAtLogin))
      } catch (_: Exception) {
        System.getLogger("dev.skillbill.tray").log(System.Logger.Level.WARNING, "settings_rollback_failed")
      }
      mutable.update { it.copy(settings = settings.copy(startAtLogin = previous.startAtLogin), settingsProblem = Problem("login_setting_failed", "Start at login could not be changed.")) }
    }
    if (changedPollInputs) {
      selectionGeneration.incrementAndGet()
      mutable.update { it.copy(detail = null, detailProblem = null, stale = it.board != null, detailExtrasStale = it.selected != null) }
      mutable.value.selected?.let { enqueueDetailRefresh(it, boardRevision) }
      refresh()
    }
  }

  override fun chooseTab(tab: String) {
    if (tab !in setOf("Goals", "Phases")) return
    updateSettings(mutable.value.settings.copy(lastPopoverTab = tab))
  }

  override fun trayUnavailable() {
    mutable.update { it.copy(trayProbeComplete = true, trayProblem = Problem("tray_unavailable", "The system tray is unavailable. Skill Bill remains open in its desktop window.")) }
  }

  override fun trayAvailable() {
    mutable.update { it.copy(trayProbeComplete = true, trayProblem = null) }
  }

  override fun perform(action: String, identity: WorkIdentity) {
    if (action !in setOf("pause", "stop")) return
    val item = mutable.value.board?.items?.singleOrNull { it.identity == identity } ?: return
    if (mutable.value.stale || item.freshness != Freshness.FRESH || !eligibleForControl(item) || (action == "pause" && item.pauseRequested)) return
    val key = "${action}:$identity"
    if (!busyActions.add(key)) return
    mutable.update { it.copy(actionProblem = null) }
    scope.launch {
      try {
        val current = mutable.value.board?.items?.singleOrNull { it.identity == identity }
        if (mutable.value.stale || current == null || current.freshness != Freshness.FRESH || !eligibleForControl(current) || (action == "pause" && current.pauseRequested)) return@launch
        val result = if (action == "pause") source.pause(current) else source.stop(current)
        mutable.update { it.copy(actionProblem = (result as? Outcome.Failure)?.problem) }
      } catch (_: Exception) {
        System.getLogger("dev.skillbill.tray").log(System.Logger.Level.WARNING, "goal_control_failed")
      } finally {
        busyActions.remove(key)
        source.refresh()
      }
    }
  }

  override fun close() { scope.cancel() }

  private fun loadInitialSettings(): Pair<AppSettings, Problem?> = try {
    settingsStore.load() to null
  } catch (_: Exception) {
    System.getLogger("dev.skillbill.tray").log(System.Logger.Level.WARNING, "settings_load_failed")
    AppSettings() to Problem("settings_unavailable", "Settings could not be loaded.")
  }

  private suspend fun pollLoop() {
    while (currentCoroutineContext().isActive) {
      val (settings, generation) = synchronized(pollInputsLock) { mutable.value.settings to settingsGeneration.get() }
      boardPageMutex.withLock { loadBoardPages(settings, generation) }
      if (generation == settingsGeneration.get()) {
        withTimeoutOrNull(mutable.value.settings.pollSeconds * 1000L) { source.nextRefresh() }
      }
    }
  }

  private suspend fun loadBoardPages(settings: AppSettings, generation: Long, continuation: String? = null) {
    val previousBoard = mutable.value.board
    val received = linkedMapOf<WorkIdentity, WorkItem>()
    if (continuation != null) previousBoard?.items?.forEach { received[it.identity] = it }
    val pageLimit = if (continuation == null) loadedBoardPages else 1
    var receivedPages = 0
    val diagnostics = linkedSetOf<String>()
    val seenCursors = mutableSetOf<String>()
    var cursor: String? = continuation
    var observedAt: java.time.Instant? = previousBoard?.observedAt?.takeIf { continuation != null }
    mutable.update { it.copy(boardLoading = true) }
    do {
      val page = source.board(settings.retention, cursor)
      if (generation != settingsGeneration.get()) return
      val result = when (page) {
        is Outcome.Failure -> page
        is Outcome.Value -> {
          val board = page.value
          if ((observedAt != null && board.observedAt != observedAt) || board.finishedWithin != settings.retention ||
            (board.nextCursor != null && !seenCursors.add(board.nextCursor))) {
            System.getLogger("dev.skillbill.tray").log(System.Logger.Level.WARNING, "board_pagination_invalid")
            Outcome.Failure(Problem("malformed_response", "Skill Bill returned an invalid board page."))
          } else {
            observedAt = board.observedAt
            cursor = board.nextCursor
            receivedPages++
            if (continuation != null) loadedBoardPages++
            board.items.forEach { received[it.identity] = it }
            diagnostics.addAll(board.diagnostics)
            val visible = if (cursor == null || receivedPages >= pageLimit) received.values.toList() else {
              (previousBoard?.items.orEmpty().map { it.copy(freshness = Freshness.STALE) } + received.values).associateBy { it.identity }.values.toList()
            }
            Outcome.Value(board.copy(items = visible, diagnostics = diagnostics.toList()))
          }
        }
      }
      val loading = result is Outcome.Value && result.value.nextCursor != null && result.value.problem == null && receivedPages < pageLimit
      synchronized(pollInputsLock) {
        val oldBoard = mutable.value.board
        when (result) {
          is Outcome.Value -> {
            if (generation == settingsGeneration.get()) {
              val board = result.value
              if (board.problem != null && oldBoard != null) {
                freezeBoard(oldBoard, board.problem)
              } else {
                if (board.problem == null) {
                  source.acceptBoard(board)
                  if (!loading) publishTransitions(board)
                  boardRevision++
                  val now = java.time.Instant.now()
                  val previousItems = oldBoard?.items?.associateBy { it.identity }.orEmpty()
                  val previousState = mutable.value
                  val previousDurations = previousState.displayDurations
                  val displayed = board.items.associate { item ->
                    val previous = previousItems[item.identity]
                    val previousDuration = previousDurations[item.identity]
                    val frozen = when {
                      item.freshness != Freshness.STALE || previous == null -> null
                      previousState.stale || previous.freshness == Freshness.STALE -> previousDuration?.forSubtask(item.currentSubtaskId)
                      else -> DisplayDurations(
                        displayedDuration(previous.activeDurationMs, previous.activeDurationAsOf, now, previous.freshness, previous.lifecycle == Lifecycle.ACTIVE),
                        item.currentSubtaskId,
                        if (previous.currentSubtaskId == item.currentSubtaskId) displayedDuration(previous.subtaskActiveDurationMs, previous.subtaskActiveDurationAsOf, now, previous.freshness, previous.lifecycle == Lifecycle.ACTIVE) else null,
                      )
                    }
                    item.identity to (frozen ?: DisplayDurations(
                      displayedDuration(item.activeDurationMs, item.activeDurationAsOf, board.observedAt, item.freshness, item.lifecycle == Lifecycle.ACTIVE),
                      item.currentSubtaskId,
                      displayedDuration(item.subtaskActiveDurationMs, item.subtaskActiveDurationAsOf, board.observedAt, item.freshness, item.lifecycle == Lifecycle.ACTIVE),
                    )).let { projection -> if (frozen == null && item.freshness == Freshness.STALE) previousDurations[item.identity] ?: projection else projection }
                  }
                  mutable.update { state ->
                    val previous = state.board?.items?.singleOrNull { it.identity == state.selected }
                    val replacement = previous?.let { old -> board.items.singleOrNull { current ->
                      current.identity == old.identity || (old.scope == ExecutionScope.STANDALONE_PHASE && old.issueKey != null &&
                        current.scope == old.scope && current.issueKey == old.issueKey && current.phaseId == old.phaseId &&
                        current.identity.repositoryIdentity == old.identity.repositoryIdentity)
                    } }
                    val selected = replacement?.identity ?: state.selected
                    val detail = state.detail?.let { current -> replacement?.let { current.copy(item = it) } ?: current }
                    state.copy(board = board, selected = selected, problem = null, stale = false, displayDurations = displayed, detail = detail, detailExtrasStale = selected != null, boardLoading = loading)
                  }
                  if (!loading) mutable.value.selected?.let { enqueueDetailRefresh(it, boardRevision) }
                } else mutable.update { it.copy(board = board, problem = board.problem, stale = false, boardLoading = false) }
              }
            }
          }
          is Outcome.Failure -> if (generation == settingsGeneration.get()) if (oldBoard == null) mutable.update { it.copy(problem = result.problem, boardLoading = false) } else freezeBoard(oldBoard, result.problem)
        }
      }
      if (!loading) return
    } while (currentCoroutineContext().isActive)
  }

  private fun freezeBoard(board: Board, problem: Problem) {
    source.invalidateBoard()
    val now = java.time.Instant.now()
    val previousState = mutable.value
    val previous = previousState.displayDurations
    val frozen = board.items.associate { item ->
      val existing = previous[item.identity]
      item.identity to when {
        previousState.stale -> existing?.forSubtask(item.currentSubtaskId) ?: DisplayDurations(null, item.currentSubtaskId, null)
        item.freshness == Freshness.STALE && existing != null -> existing.forSubtask(item.currentSubtaskId)
        else -> DisplayDurations(
          displayedDuration(item.activeDurationMs, item.activeDurationAsOf, now, item.freshness, item.lifecycle == Lifecycle.ACTIVE),
          item.currentSubtaskId,
          displayedDuration(item.subtaskActiveDurationMs, item.subtaskActiveDurationAsOf, now, item.freshness, item.lifecycle == Lifecycle.ACTIVE),
        )
      }
    }
    mutable.update { it.copy(problem = problem, stale = true, displayDurations = frozen, detailExtrasStale = it.selected != null, boardLoading = false) }
  }

  private fun DisplayDurations.forSubtask(subtaskId: String?) =
    if (subtaskId == this.subtaskId) this else copy(subtaskId = subtaskId, subtaskMs = null)

  private fun enqueueDetailRefresh(identity: WorkIdentity, revision: Long) {
    synchronized(detailRefreshLock) {
      pendingDetailRefresh = identity to revision
      if (detailRefreshJob == null) detailRefreshJob = scope.launch { drainDetailRefreshes() }
    }
  }

  private suspend fun drainDetailRefreshes() {
    while (true) {
      val request = synchronized(detailRefreshLock) {
        val next = pendingDetailRefresh
        pendingDetailRefresh = null
        if (next == null) detailRefreshJob = null
        next
      } ?: return
      val selection = selectionGeneration.get()
      val settings = settingsGeneration.get()
      detailPageMutex.withLock {
        if (selection == selectionGeneration.get() && settings == settingsGeneration.get()) {
          loadDetailPages(request.first, request.second, selection, settings)
        }
      }
    }
  }

  private suspend fun loadDetailPages(identity: WorkIdentity, revision: Long, selection: Long, settings: Long, continuation: String? = null) {
    val previous = mutable.value.detail?.takeIf { it.item.identity == identity }
    var received: Detail? = previous?.takeIf { continuation != null }
    var cursor: String? = continuation
    val pageLimit = if (continuation == null) loadedDetailPages else 1
    var receivedPages = 0
    val seenCursors = mutableSetOf<String>()
    mutable.update { it.copy(detailLoading = true) }
    do {
      val result = source.detail(identity, cursor)
      if (selection != selectionGeneration.get() || settings != settingsGeneration.get() || mutable.value.selected != identity) return
      when (result) {
        is Outcome.Value -> {
          val page = result.value
          if ((received != null && received.observedAt != page.observedAt) ||
            (page.nextCursor != null && !seenCursors.add(page.nextCursor))) {
            System.getLogger("dev.skillbill.tray").log(System.Logger.Level.WARNING, "detail_pagination_invalid")
            mutable.update { it.copy(detailProblem = Problem("malformed_response", "Skill Bill returned an invalid detail page."), detailLoading = false, detailExtrasStale = true) }
            return
          }
          received = mergeDetailLists(page, received)
          cursor = page.nextCursor
          receivedPages++
          if (continuation != null) loadedDetailPages++
          val loading = cursor != null && receivedPages < pageLimit
          val visible = if (!loading) received else mergeDetailLists(received, previous)
          mutable.update { state ->
            if (selection == selectionGeneration.get() && settings == settingsGeneration.get() && state.selected == identity) {
              val boardItem = state.board?.items?.singleOrNull { it.identity == identity }
              state.copy(detail = visible.copy(item = boardItem ?: visible.item), detailProblem = null,
                detailExtrasStale = state.stale || boardRevision != revision, detailLoading = loading)
            } else state
          }
        }
        is Outcome.Failure -> {
          mutable.update { state ->
            if (selection == selectionGeneration.get() && settings == settingsGeneration.get() && state.selected == identity) {
              state.copy(detailProblem = result.problem, detailExtrasStale = true, detailLoading = false)
            } else state
          }
          return
        }
      }
      if (receivedPages >= pageLimit) return
    } while (cursor != null && currentCoroutineContext().isActive)
  }

  private fun mergeDetailLists(page: Detail, previous: Detail?): Detail = page.copy(
    subtasks = (previous?.subtasks.orEmpty() + page.subtasks).associateBy { it.id }.values.toList(),
    phases = (previous?.phases.orEmpty() + page.phases).associateBy { it.id }.values.toList(),
    validation = (previous?.validation.orEmpty() + page.validation).associateBy { it.subtaskId }.values.toList(),
    executionHistory = (previous?.executionHistory.orEmpty() + page.executionHistory).associateBy { it.invocationId }.values.toList(),
  )

  private fun publishTransitions(board: Board) {
    transitions.observe(board).forEach { (item, transition) ->
      val key = "${item.identity}:$transition:${board.observedAt}"
      if (announced.add(key) && mutable.value.settings.notifications[transition] == true) synchronized(notificationLock) {
        native.notifyAuthorized(item.summary, item.currentStepLabel, item.identity,
          { send -> synchronized(notificationLock) { if (mutable.value.settings.notifications[transition] == true) send() } }, ::select)
      }
    }
    while (announced.size > 1000) announced.remove(announced.first())
    transitions.retain(board.items.mapTo(mutableSetOf()) { it.identity })
  }
}

fun enabledTransitions(events: List<Pair<WorkItem, String>>, settings: AppSettings): List<Pair<WorkItem, String>> =
  events.filter { (_, transition) -> transition in allowedTransitions && settings.notifications[transition] == true }

class TransitionTracker {
  private val last = mutableMapOf<WorkIdentity, Lifecycle>()

  fun observe(board: Board): List<Pair<WorkItem, String>> = buildList {
    board.items.forEach { item ->
      if (item.freshness != Freshness.FRESH) return@forEach
      val prior = last.put(item.identity, item.lifecycle)
      if (prior == null) return@forEach
      val event = when {
        item.lifecycle == Lifecycle.BLOCKED && prior != Lifecycle.BLOCKED -> "blocked"
        item.lifecycle == Lifecycle.FAILED && prior != Lifecycle.FAILED -> "failed"
        item.family == WorkFamily.FEATURE_GOAL && item.lifecycle == Lifecycle.TERMINAL && prior != Lifecycle.TERMINAL -> "goal_completed"
        item.lifecycle == Lifecycle.PAUSED && prior != Lifecycle.PAUSED -> "pause_took_effect"
        item.scope == ExecutionScope.STANDALONE_PHASE && item.lifecycle == Lifecycle.TERMINAL && prior != Lifecycle.TERMINAL -> "standalone_phase_finished"
        else -> null
      }
      if (event != null) add(item to event)
    }
  }

  fun retain(identities: Set<WorkIdentity>) { last.keys.retainAll(identities) }
}

fun eligibleForControl(item: WorkItem): Boolean = item.family == WorkFamily.FEATURE_GOAL && item.lifecycle == Lifecycle.ACTIVE && !item.issueKey.isNullOrBlank()

data class BoardProjection(
  val running: Int,
  val needsYou: Int,
  val done24h: Int,
  val goals: List<WorkItem>,
  val phases: List<WorkItem>,
  val livePhases: List<WorkItem> = phases,
  val finishedPhases: List<WorkItem> = emptyList(),
)

fun WorkItem.isPhase(): Boolean = family != WorkFamily.FEATURE_GOAL &&
  (scope == ExecutionScope.STANDALONE_PHASE || family == WorkFamily.FEATURE_TASK_RUNTIME || family == WorkFamily.FEATURE_VERIFY)

fun project(board: Board, now: java.time.Instant): BoardProjection {
  val goals = board.items.filter { it.family == WorkFamily.FEATURE_GOAL && it.lifecycle in setOf(Lifecycle.BLOCKED, Lifecycle.FAILED, Lifecycle.ACTIVE, Lifecycle.PAUSED) }.sortedWith(
    compareBy<WorkItem> { when {
      it.lifecycle == Lifecycle.BLOCKED -> 0
      it.lifecycle == Lifecycle.FAILED -> 1
      it.lifecycle == Lifecycle.ACTIVE && (it.planningState == null || it.planningState == "prepared") -> 2
      it.lifecycle == Lifecycle.ACTIVE && it.planningState != null -> 3
      it.lifecycle == Lifecycle.PAUSED -> 4
      else -> 5
    } }.thenBy { it.identity.id }.thenBy { it.identity.repositoryIdentity }
  )
  val phaseItems = board.items.filter { it.isPhase() }
  val finished = phaseItems.filter { it.finishedAt?.let { end -> !end.isBefore(now.minus(Duration.ofHours(1))) && !end.isAfter(now) } == true }
  val live = phaseItems.filter { it.lifecycle in setOf(Lifecycle.ACTIVE, Lifecycle.PAUSED, Lifecycle.BLOCKED, Lifecycle.FAILED) && it !in finished }
  return BoardProjection(board.items.count { it.lifecycle == Lifecycle.ACTIVE }, board.items.count {
    it.lifecycle == Lifecycle.BLOCKED || it.lifecycle == Lifecycle.FAILED || it.pauseReasonCode == "awaiting_operator_decision"
  }, board.items.count { it.lifecycle == Lifecycle.TERMINAL && it.finishedAt?.let { done -> !done.isBefore(board.observedAt.minus(Duration.ofHours(24))) && !done.isAfter(board.observedAt) } == true }, goals, live + finished, live, finished)
}

fun displayedDuration(baseMs: Long?, asOf: java.time.Instant?, now: java.time.Instant, freshness: Freshness, live: Boolean): Long? {
  if (baseMs == null) return null
  if (!live || freshness != Freshness.FRESH || asOf == null) return baseMs
  return (baseMs + Duration.between(asOf, now).toMillis().coerceAtLeast(0)).coerceAtLeast(baseMs)
}
