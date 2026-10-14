package dev.skillbill.tray.core.domain.repository

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.BoardRetention
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
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
  suspend fun cancel(item: WorkItem): Outcome<Unit> = Outcome.Failure(Problem(ProblemCode.UNKNOWN, "Cancellation is unavailable."))
  suspend fun launch(item: WorkItem, agentId: String?): Outcome<Unit> = Outcome.Failure(Problem(ProblemCode.UNKNOWN, "Launching is unavailable."))
}

interface WorkStoreSession {
  suspend fun selectDatabase(path: String?)
  fun close()
  suspend fun closeAndJoin()
}

/** Preference access. App-scope teardown owns closing the backing store, so there is no `close` here. */
interface SettingsRepository {
  val settings: Flow<SettingsState>
  suspend fun update(transform: (AppSettings) -> AppSettings): Outcome<AppSettings>
}

sealed interface Outcome<out T> {
  data class Value<T>(val value: T) : Outcome<T>
  data class Failure(val problem: Problem) : Outcome<Nothing>
}
