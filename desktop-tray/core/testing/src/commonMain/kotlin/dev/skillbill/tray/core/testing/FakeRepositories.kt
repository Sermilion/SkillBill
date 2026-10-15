package dev.skillbill.tray.core.testing

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.BoardArrangement
import dev.skillbill.tray.core.domain.model.BoardRetention
import dev.skillbill.tray.core.domain.model.ControlConfirmation
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.TrayStatus
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.domain.repository.WorkPolicyRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreSession
import dev.skillbill.tray.core.domain.repository.WorkStoreStatusRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Test doubles for the domain repository ports. They hold no logic of their own: a test pushes what the "runtime" reports
// and parks a call on a barrier (a CompletableDeferred it owns) to decide exactly when that call returns.

data class ObserveCall(
  val section: WorkSection,
  val retention: Retention,
  val filter: String,
  val historyFilter: HistoryFilter,
  val anchor: String?,
)

data class BoardReadCall(val section: WorkSection, val cursor: String?, val filter: String, val historyFilter: HistoryFilter)

class FakeWorkBoardRepository : WorkBoardRepository {
  private val observed = WorkSection.entries.associateWith { MutableSharedFlow<Outcome<Board>>(extraBufferCapacity = 64) }
  private val events = MutableSharedFlow<Set<WorkSection>>(extraBufferCapacity = 64)

  val observeCalls = mutableListOf<ObserveCall>()
  val readCalls = mutableListOf<BoardReadCall>()
  val retained = mutableListOf<BoardRetention>()
  var refreshCount = 0
    private set

  /** Answers a [board] read. Park it on a barrier by suspending inside; the default reports no further page. */
  var onBoard: suspend (BoardReadCall) -> Outcome<Board> = { Outcome.Failure(Problem(ProblemCode.UNKNOWN, "no page configured")) }

  override val refreshEvents: Flow<Set<WorkSection>> = events

  override fun observe(
    section: WorkSection,
    retention: Retention,
    filter: String,
    historyFilter: HistoryFilter,
    anchor: String?,
  ): Flow<Outcome<Board>> {
    observeCalls += ObserveCall(section, retention, filter, historyFilter, anchor)
    return observed.getValue(section)
  }

  override suspend fun board(section: WorkSection, retention: Retention, cursor: String?, filter: String, historyFilter: HistoryFilter): Outcome<Board> {
    val call = BoardReadCall(section, cursor, filter, historyFilter)
    readCalls += call
    return onBoard(call)
  }

  override fun retainPages(retention: BoardRetention) {
    retained += retention
  }

  override suspend fun refresh() {
    refreshCount++
  }

  /** Whether something is collecting [section]; a test waits for this before it emits. */
  fun subscribers(section: WorkSection): Int = observed.getValue(section).subscriptionCount.value

  fun emit(section: WorkSection, outcome: Outcome<Board>) {
    check(observed.getValue(section).tryEmit(outcome)) { "no room to emit for $section" }
  }

  fun emitRefresh(sections: Set<WorkSection>) {
    check(events.tryEmit(sections))
  }
}

class FakeWorkDetailRepository : WorkDetailRepository {
  private val observed = MutableSharedFlow<Outcome<Detail>>(extraBufferCapacity = 64)

  val observedIdentities = mutableListOf<WorkIdentity>()
  val readCalls = mutableListOf<DetailReadCall>()

  var onDetail: suspend (DetailReadCall) -> Outcome<Detail> = { Outcome.Failure(Problem(ProblemCode.UNKNOWN, "no detail configured")) }

  override fun observeDetail(identity: WorkIdentity): Flow<Outcome<Detail>> {
    observedIdentities += identity
    return observed
  }

  override suspend fun detail(identity: WorkIdentity, cursor: String?, list: DetailList?, anchor: String?): Outcome<Detail> {
    val call = DetailReadCall(identity, cursor, list, anchor)
    readCalls += call
    return onDetail(call)
  }

  fun subscribers(): Int = observed.subscriptionCount.value

  fun emit(outcome: Outcome<Detail>) {
    check(observed.tryEmit(outcome))
  }
}

data class DetailReadCall(val identity: WorkIdentity, val cursor: String?, val list: DetailList?, val anchor: String?)

class FakeWorkControlRepository : WorkControlRepository {
  val calls = mutableListOf<String>()

  var onPause: suspend (WorkItem) -> Outcome<ControlConfirmation> = { Outcome.Value(ControlConfirmation.INTENT_RECORDED) }
  var onStop: suspend (WorkItem) -> Outcome<ControlConfirmation> = { Outcome.Value(ControlConfirmation.EXIT_CONFIRMED) }
  var onCancel: suspend (WorkItem) -> Outcome<Unit> = { Outcome.Value(Unit) }
  var onLaunch: suspend (WorkItem, String?) -> Outcome<Unit> = { _, _ -> Outcome.Value(Unit) }

  override suspend fun pause(item: WorkItem): Outcome<ControlConfirmation> {
    calls += "pause:${item.identity.id}"
    return onPause(item)
  }

  override suspend fun stop(item: WorkItem): Outcome<ControlConfirmation> {
    calls += "stop:${item.identity.id}"
    return onStop(item)
  }

  override suspend fun cancel(item: WorkItem): Outcome<Unit> {
    calls += "cancel:${item.identity.id}"
    return onCancel(item)
  }

  override suspend fun launch(item: WorkItem, agentId: String?): Outcome<Unit> {
    calls += "launch:${item.identity.id}:${agentId.orEmpty()}"
    return onLaunch(item, agentId)
  }
}

class FakeWorkPolicyRepository : WorkPolicyRepository {
  override fun arrange(items: List<WorkItem>): BoardArrangement = BoardArrangement(
    trayStatus = TrayStatus.IDLE,
    running = 0,
    needsYou = 0,
    doneWithinDay = 0,
    goals = items.map { it.identity },
    livePhases = emptyList(),
    finishedPhases = emptyList(),
  )
}

class FakeWorkStoreSession : WorkStoreSession {
  val selectedPaths = mutableListOf<String?>()
  private val generations = MutableStateFlow(0L)

  override val generation: StateFlow<Long> = generations.asStateFlow()

  /** Runs inside [selectDatabase] before the generation advances; park it on a barrier to hold a switch open. */
  var onSelect: suspend (String?) -> Unit = {}
  var closes = 0
    private set

  override suspend fun selectDatabase(path: String?) {
    selectedPaths += path
    onSelect(path)
    generations.value += 1
  }

  /** A same-path replacement the data layer found on its own: the generation advances with no selection. */
  fun replaceInPlace() {
    generations.value += 1
  }

  override fun close() {
    closes += 1
  }

  override suspend fun closeAndJoin() {
    closes += 1
  }
}

class FakeWorkStoreStatusRepository(initial: String? = null) : WorkStoreStatusRepository {
  private val path = MutableStateFlow(initial)

  override val effectiveDatabasePath: Flow<String?> = path.asStateFlow()

  fun publish(effectiveDatabasePath: String?) {
    path.value = effectiveDatabasePath
  }
}

class FakeSettingsRepository(initial: SettingsState = SettingsState.Loading) : SettingsRepository {
  private val state = MutableStateFlow(initial)

  override val settings: Flow<SettingsState> = state.asStateFlow()

  val updates = mutableListOf<AppSettings>()

  /** Runs the write; park it on a barrier by suspending inside, or fail it by returning a [Outcome.Failure]. */
  var onUpdate: suspend (AppSettings) -> Outcome<AppSettings> = { Outcome.Value(it) }

  override suspend fun update(transform: (AppSettings) -> AppSettings): Outcome<AppSettings> {
    val current = (state.value as? SettingsState.Ready)?.settings
      ?: return Outcome.Failure(Problem(ProblemCode.SETTINGS_UNAVAILABLE, "settings are not ready"))
    val next = transform(current)
    updates += next
    val result = onUpdate(next)
    if (result is Outcome.Value) state.value = SettingsState.Ready(result.value)
    return result
  }

  fun publish(settings: SettingsState) {
    state.value = settings
  }
}
