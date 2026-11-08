package dev.skillbill.desktop.core.datastore

import androidx.datastore.preferences.core.Preferences
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.NewTaskChatLayout
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.domain.settings.TrayTab
import dev.skillbill.desktop.core.domain.settings.CriterionMarkKey
import dev.skillbill.desktop.core.domain.settings.CriterionMark
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind

/** Decodes tolerantly: an unknown or out-of-range stored value falls back to its default and is passed to [onUnsupported] as `key=raw`. */
internal fun Preferences.toAppSettings(onUnsupported: (String) -> Unit): AppSettings {
  fun <T> decode(key: Preferences.Key<String>, default: T, match: (String) -> T?): T {
    val raw = this[key] ?: return default
    return match(raw) ?: default.also { onUnsupported("${key.name}=$raw") }
  }
  val storedHeight = this[AppPreferenceKeys.TERMINAL_HEIGHT_DP]
  val height = storedHeight?.coerceIn(AppSettings.TERMINAL_HEIGHT_MIN_DP, AppSettings.TERMINAL_HEIGHT_MAX_DP)
  if (height != storedHeight) onUnsupported("${AppPreferenceKeys.TERMINAL_HEIGHT_DP.name}=$storedHeight")
  val storedChatWidth = this[AppPreferenceKeys.CHAT_WIDTH_DP]
  val chatWidth = storedChatWidth?.coerceIn(AppSettings.CHAT_WIDTH_MIN_DP, AppSettings.CHAT_WIDTH_MAX_DP)
  if (chatWidth != storedChatWidth) onUnsupported("${AppPreferenceKeys.CHAT_WIDTH_DP.name}=$storedChatWidth")
  val favoriteWork = this[AppPreferenceKeys.FAVORITE_WORK].orEmpty().mapNotNull { encoded ->
    FavoriteWorkKeyEncoding.decode(encoded).also { if (it == null) onUnsupported("${AppPreferenceKeys.FAVORITE_WORK.name}=$encoded") }
  }.toSet()
  val favoriteSpecs = this[AppPreferenceKeys.FAVORITE_SPECS].orEmpty().mapNotNull { encoded ->
    FavoriteSpecKeyEncoding.decode(encoded).also { if (it == null) onUnsupported("${AppPreferenceKeys.FAVORITE_SPECS.name}=$encoded") }
  }.toSet()
  val qaDevice = qaDevice { onUnsupported(it) }
  return AppSettings(
    databasePath = this[AppPreferenceKeys.DATABASE_PATH],
    claudeConfigDir = this[AppPreferenceKeys.CLAUDE_CONFIG_DIR],
    historyWindow = decode(AppPreferenceKeys.HISTORY_RETENTION, HistoryWindow.DEFAULT) { raw ->
      HistoryWindow.entries.firstOrNull { it.wireValue == raw }
    },
    notifications = NotificationKind.entries.associateWith { this[AppPreferenceKeys.notificationKey(it)] ?: it.defaultEnabled },
    startAtLogin = this[AppPreferenceKeys.START_AT_LOGIN] ?: false,
    terminalHeightDp = height ?: AppSettings.TERMINAL_HEIGHT_DEFAULT_DP,
    chatWidthDp = chatWidth ?: AppSettings.CHAT_WIDTH_DEFAULT_DP,
    trayLastTab = decode(AppPreferenceKeys.TRAY_LAST_TAB, TrayTab.DEFAULT) { raw -> TrayTab.entries.firstOrNull { it.wireValue == raw } },
    favoriteWork = favoriteWork,
    favoriteSpecs = favoriteSpecs,
    newTaskChatLayout = decode(AppPreferenceKeys.NEW_TASK_CHAT_LAYOUT, NewTaskChatLayout.DEFAULT) { raw ->
      NewTaskChatLayout.entries.firstOrNull { it.wireValue == raw }
    },
    qaDevice = qaDevice,
    defaultAgentId = this[AppPreferenceKeys.DEFAULT_AGENT]?.takeIf(String::isNotBlank),
  )
}

internal fun Preferences.qaDevice(onUnsupported: (String) -> Unit): QaDevicePick? {
  val kind = this[AppPreferenceKeys.QA_DEVICE_KIND]
  val value = this[AppPreferenceKeys.QA_DEVICE_VALUE]
  val displayName = this[AppPreferenceKeys.QA_DEVICE_DISPLAY_NAME]
  if (kind == null && value == null && displayName == null) return null
  val decodedKind = QaDevicePickKind.entries.firstOrNull { it.wireValue == kind }
  val pick = if (decodedKind != null && value != null && displayName != null) QaDevicePick(decodedKind, value, displayName) else null
  if (pick == null || !pick.isValid) {
    onUnsupported("qa_device_invalid")
    return null
  }
  return pick
}

internal fun Preferences.criterionMarks(): Map<CriterionMarkKey, CriterionMark> =
  this[AppPreferenceKeys.CRITERION_MARKS].orEmpty().mapNotNull(CriterionMarkEncoding::decode).toMap()
