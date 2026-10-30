package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.domain.settings.TrayTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory settings that apply the same blank-path rule as the DataStore repository. */
class FakeAppSettingsRepository(initial: AppSettings = AppSettings.DEFAULT) : AppSettingsRepository {
  override val settings = MutableStateFlow(initial)

  override suspend fun setDatabasePath(path: String?): Outcome<Unit, SettingsRejection> {
    if (!path.isNullOrEmpty() && path.isBlank()) return Outcome.Failure(SettingsRejection.BlankDatabasePath)
    return change { it.copy(databasePath = path?.trim()?.ifEmpty { null }) }
  }

  override suspend fun setClaudeConfigDir(directory: String?) =
    change { it.copy(claudeConfigDir = directory?.trim()?.ifEmpty { null }) }

  override suspend fun setHistoryWindow(window: HistoryWindow) = change { it.copy(historyWindow = window) }

  override suspend fun setNotification(kind: NotificationKind, enabled: Boolean) =
    change { it.copy(notifications = it.notifications + (kind to enabled)) }

  override suspend fun setStartAtLogin(enabled: Boolean) = change { it.copy(startAtLogin = enabled) }

  override suspend fun setTerminalHeightDp(heightDp: Int) = change {
    it.copy(terminalHeightDp = heightDp.coerceIn(AppSettings.TERMINAL_HEIGHT_MIN_DP, AppSettings.TERMINAL_HEIGHT_MAX_DP))
  }

  override suspend fun setTrayLastTab(tab: TrayTab) = change { it.copy(trayLastTab = tab) }

  private fun change(transform: (AppSettings) -> AppSettings): Outcome<Unit, SettingsRejection> {
    settings.update(transform)
    return Outcome.Success(Unit)
  }
}
