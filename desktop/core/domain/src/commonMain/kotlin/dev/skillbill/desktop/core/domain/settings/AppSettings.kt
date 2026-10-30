package dev.skillbill.desktop.core.domain.settings

/**
 * The app's preferences. A null [databasePath] means the runtime default (`SKILL_BILL_DB`, then `~/.skill-bill/review-metrics.db`),
 * and a null [claudeConfigDir] leaves the agent's own default.
 */
data class AppSettings(
  val databasePath: String?,
  val claudeConfigDir: String?,
  val historyWindow: HistoryWindow,
  val notifications: Map<NotificationKind, Boolean>,
  val startAtLogin: Boolean,
  val terminalHeightDp: Int,
  val trayLastTab: TrayTab,
) {
  companion object {
    const val TERMINAL_HEIGHT_DEFAULT_DP = 200
    const val TERMINAL_HEIGHT_MIN_DP = 120
    const val TERMINAL_HEIGHT_MAX_DP = 1200

    val DEFAULT = AppSettings(
      databasePath = null,
      claudeConfigDir = null,
      historyWindow = HistoryWindow.DEFAULT,
      notifications = NotificationKind.entries.associateWith { it.defaultEnabled },
      startAtLogin = false,
      terminalHeightDp = TERMINAL_HEIGHT_DEFAULT_DP,
      trayLastTab = TrayTab.DEFAULT,
    )
  }
}

/** How far back the board shows finished work. */
enum class HistoryWindow(val wireValue: String) {
  SixHours("6h"),
  Day("24h"),
  Week("7d"),
  ;

  companion object {
    val DEFAULT = Day
  }
}

enum class NotificationKind(val defaultEnabled: Boolean = true) {
  Blocked,
  Failed,
  GoalCompleted,
  PauseTookEffect,
  StandalonePhaseFinished(defaultEnabled = false),
  QaIncomplete,
}

enum class TrayTab(val wireValue: String) {
  Goals("goals"),
  Phases("phases"),
  Operations("operations"),
  ;

  companion object {
    val DEFAULT = Goals
  }
}
