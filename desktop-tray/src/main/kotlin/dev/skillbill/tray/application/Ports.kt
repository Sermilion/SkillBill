package dev.skillbill.tray.application

import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

interface WorkBoardRepository {
  val refreshEvents: Flow<Set<WorkSection>>
  fun observe(section: WorkSection, retention: Retention, filter: String = "", historyFilter: HistoryFilter = HistoryFilter.ALL, anchor: String? = null): Flow<Outcome<Board>>
  suspend fun board(section: WorkSection, retention: Retention, cursor: String? = null, filter: String = "", historyFilter: HistoryFilter = HistoryFilter.ALL): Outcome<Board>
  fun retainPages(retention: BoardRetention)
  suspend fun refresh()
}

interface WorkDetailRepository {
  fun observeDetail(identity: WorkIdentity): Flow<Outcome<Detail>>
  suspend fun detail(identity: WorkIdentity, cursor: String? = null, list: DetailList? = null, startOffset: Int = 0): Outcome<Detail>
}

interface WorkControlRepository {
  suspend fun pause(item: WorkItem): Outcome<Unit>
  suspend fun stop(item: WorkItem): Outcome<Unit>
}

interface WorkStoreSession {
  suspend fun selectDatabase(path: String?)
  fun close()
  suspend fun closeAndJoin()
}

interface SettingsStore {
  val settings: Flow<Outcome<AppSettings>>
  suspend fun update(transform: (AppSettings) -> AppSettings): Outcome<Unit>
  fun close()
  suspend fun closeAndJoin()
}

interface NativeServices {
  suspend fun hasSystemTray(): Boolean = true
  fun notify(title: String, body: String, identity: WorkIdentity, onClick: (WorkIdentity) -> Unit)
  fun notifyAuthorized(title: String, body: String, identity: WorkIdentity, authorizeAndSend: (() -> Unit) -> Unit, onClick: (WorkIdentity) -> Unit) {
    authorizeAndSend { notify(title, body, identity, onClick) }
  }
  fun setStartAtLogin(enabled: Boolean): Boolean
}

sealed interface Outcome<out T> {
  data class Value<T>(val value: T) : Outcome<T>
  data class Failure(val problem: Problem) : Outcome<Nothing>
}

data class DashboardState(
  val settings: AppSettings,
  val settingsProblem: Problem? = null,
  val board: Board? = null,
  val problem: Problem? = null,
  val stale: Boolean = false,
  val selected: WorkIdentity? = null,
  val detail: Detail? = null,
  val detailProblem: Problem? = null,
  val actionProblem: Problem? = null,
  val displayDurations: Map<WorkIdentity, DisplayDurations> = emptyMap(),
  val detailExtrasStale: Boolean = false,
  val trayProblem: Problem? = null,
  val trayProbeComplete: Boolean = false,
  val boardLoading: Boolean = true,
  val detailLoading: Boolean = false,
  val detailListLoading: Set<DetailList> = emptySet(),
  val sectionLoadStates: Map<WorkSection, ListLoadState> = emptyMap(),
  val detailLoadStates: Map<DetailList, ListLoadState> = emptyMap(),
  val sections: Map<WorkSection, Board> = emptyMap(),
  val sectionLoading: Set<WorkSection> = setOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES),
  val sectionProblems: Map<WorkSection, Problem> = emptyMap(),
  val filter: String = "",
  val databasePath: String? = null,
)

enum class ListLoadState { INITIAL_LOADING, READY, READY_EMPTY, REFRESHING, APPENDING, APPEND_FAILED, FAILED, EXHAUSTED }

data class DisplayDurations(val goalMs: Long?, val subtaskId: String?, val subtaskMs: Long?)

interface DashboardModel {
  val state: StateFlow<DashboardState>
  fun refresh()
  fun loadMoreBoard()
  fun loadMore(section: WorkSection)
  fun observeHistory(enabled: Boolean)
  fun filter(value: String)
  fun loadMoreDetail(list: DetailList)
  fun select(identity: WorkIdentity)
  fun updateSettings(settings: AppSettings)
  fun chooseTab(tab: String)
  fun trayAvailable()
  fun trayUnavailable()
  fun perform(action: String, identity: WorkIdentity)
  fun perform(action: String, item: WorkItem) = perform(action, item.identity)
  fun close()
}
