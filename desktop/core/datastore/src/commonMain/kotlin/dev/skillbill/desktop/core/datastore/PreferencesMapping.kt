package dev.skillbill.desktop.core.datastore

import androidx.datastore.preferences.core.Preferences
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.domain.settings.TrayTab

/** Decodes tolerantly: an unknown or out-of-range stored value falls back to its default and is passed to [onUnsupported] as `key=raw`. */
internal fun Preferences.toAppSettings(onUnsupported: (String) -> Unit): AppSettings {
  fun <T> decode(key: Preferences.Key<String>, default: T, match: (String) -> T?): T {
    val raw = this[key] ?: return default
    return match(raw) ?: default.also { onUnsupported("${key.name}=$raw") }
  }
  val storedHeight = this[AppPreferenceKeys.TERMINAL_HEIGHT_DP]
  val height = storedHeight?.coerceIn(AppSettings.TERMINAL_HEIGHT_MIN_DP, AppSettings.TERMINAL_HEIGHT_MAX_DP)
  if (height != storedHeight) onUnsupported("${AppPreferenceKeys.TERMINAL_HEIGHT_DP.name}=$storedHeight")
  return AppSettings(
    databasePath = this[AppPreferenceKeys.DATABASE_PATH],
    claudeConfigDir = this[AppPreferenceKeys.CLAUDE_CONFIG_DIR],
    historyWindow = decode(AppPreferenceKeys.HISTORY_RETENTION, HistoryWindow.DEFAULT) { raw ->
      HistoryWindow.entries.firstOrNull { it.wireValue == raw }
    },
    notifications = NotificationKind.entries.associateWith { this[AppPreferenceKeys.notificationKey(it)] ?: it.defaultEnabled },
    startAtLogin = this[AppPreferenceKeys.START_AT_LOGIN] ?: false,
    terminalHeightDp = height ?: AppSettings.TERMINAL_HEIGHT_DEFAULT_DP,
    trayLastTab = decode(AppPreferenceKeys.TRAY_LAST_TAB, TrayTab.DEFAULT) { raw -> TrayTab.entries.firstOrNull { it.wireValue == raw } },
  )
}
