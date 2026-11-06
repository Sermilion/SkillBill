package dev.skillbill.desktop.core.domain.settings

import dev.skillbill.desktop.core.common.Outcome
import kotlinx.coroutines.flow.Flow

/** The app's preferences. Expected refusals come back as [SettingsRejection], never as exceptions. */
interface AppSettingsRepository {
  val settings: Flow<AppSettings>

  /** The persisted favorite membership, with read failures kept distinct from an empty set. */
  val favoriteWork: Flow<Outcome<Set<FavoriteWorkKey>, SettingsRejection>>

  /** The persisted spec-bundle favorite membership, with read failures kept distinct from an empty set. */
  val favoriteSpecs: Flow<Outcome<Set<FavoriteSpecKey>, SettingsRejection>>

  /** The persisted marks on Verify criteria. */
  val criterionMarks: Flow<Outcome<Map<CriterionMarkKey, CriterionMark>, SettingsRejection>>

  /** Null or empty restores the runtime default; a path of only spaces is refused with [SettingsRejection.BlankDatabasePath]. */
  suspend fun setDatabasePath(path: String?): Outcome<Unit, SettingsRejection>

  /** Null or blank leaves the agent's default directory. */
  suspend fun setClaudeConfigDir(directory: String?): Outcome<Unit, SettingsRejection>

  suspend fun setHistoryWindow(window: HistoryWindow): Outcome<Unit, SettingsRejection>

  suspend fun setNotification(kind: NotificationKind, enabled: Boolean): Outcome<Unit, SettingsRejection>

  suspend fun setStartAtLogin(enabled: Boolean): Outcome<Unit, SettingsRejection>

  /** Clamps to [AppSettings.TERMINAL_HEIGHT_MIN_DP]..[AppSettings.TERMINAL_HEIGHT_MAX_DP]. */
  suspend fun setTerminalHeightDp(heightDp: Int): Outcome<Unit, SettingsRejection>

  /** Clamps to [AppSettings.CHAT_WIDTH_MIN_DP]..[AppSettings.CHAT_WIDTH_MAX_DP]. */
  suspend fun setChatWidthDp(widthDp: Int): Outcome<Unit, SettingsRejection>

  suspend fun setTrayLastTab(tab: TrayTab): Outcome<Unit, SettingsRejection>

  suspend fun setNewTaskChatLayout(layout: NewTaskChatLayout): Outcome<Unit, SettingsRejection>

  suspend fun setFavorite(key: FavoriteWorkKey, favorite: Boolean): Outcome<Unit, SettingsRejection>

  suspend fun setSpecFavorite(key: FavoriteSpecKey, favorite: Boolean): Outcome<Unit, SettingsRejection>

  /** Sets or clears a mark on one Verify criterion. */
  suspend fun setCriterionMark(key: CriterionMarkKey, mark: CriterionMark?): Outcome<Unit, SettingsRejection>
}

sealed interface SettingsRejection {
  data object BlankDatabasePath : SettingsRejection

  data object ReadFailed : SettingsRejection

  /** The preferences file could not be written; the previous value stays. */
  data object WriteFailed : SettingsRejection
}
