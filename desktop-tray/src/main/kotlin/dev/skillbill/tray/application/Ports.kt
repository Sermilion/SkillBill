package dev.skillbill.tray.application

import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlinx.coroutines.flow.StateFlow

interface BoardSource {
  fun setExecutableOverride(path: String?)
  suspend fun board(retention: Retention, cursor: String? = null): Outcome<Board>
  suspend fun detail(identity: WorkIdentity, cursor: String? = null): Outcome<Detail>
  suspend fun refresh()
  suspend fun nextRefresh()
  fun acceptBoard(board: Board) = Unit
  fun invalidateBoard() = Unit
  suspend fun pause(item: WorkItem): Outcome<Unit>
  suspend fun stop(item: WorkItem): Outcome<Unit>
}

interface SettingsStore {
  fun load(): AppSettings
  fun save(settings: AppSettings)
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
)

data class DisplayDurations(val goalMs: Long?, val subtaskId: String?, val subtaskMs: Long?)

interface DashboardModel {
  val state: StateFlow<DashboardState>
  fun refresh()
  fun loadMoreBoard()
  fun loadMoreDetail()
  fun select(identity: WorkIdentity)
  fun updateSettings(settings: AppSettings)
  fun chooseTab(tab: String)
  fun trayAvailable()
  fun trayUnavailable()
  fun perform(action: String, identity: WorkIdentity)
  fun perform(action: String, item: WorkItem) = perform(action, item.identity)
  fun close()
}
