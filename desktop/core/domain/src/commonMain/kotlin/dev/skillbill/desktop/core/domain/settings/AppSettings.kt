package dev.skillbill.desktop.core.domain.settings

import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.qa.QaDevicePick

/**
 * The app's preferences. A null [databasePath] means the runtime default
 * (`SKILL_BILL_REVIEW_DB`, then `~/.skill-bill/review-metrics.db`),
 * and a null [claudeConfigDir] leaves the agent's own default.
 */
data class AppSettings(
  val databasePath: String?,
  val claudeConfigDir: String?,
  val historyWindow: HistoryWindow,
  val notifications: Map<NotificationKind, Boolean>,
  val startAtLogin: Boolean,
  val terminalHeightDp: Int,
  val chatWidthDp: Int,
  val trayLastTab: TrayTab,
  val favoriteWork: Set<FavoriteWorkKey> = emptySet(),
  val favoriteSpecs: Set<FavoriteSpecKey> = emptySet(),
  val newTaskChatLayout: NewTaskChatLayout = NewTaskChatLayout.DEFAULT,
  val qaDevice: QaDevicePick? = null,
  /** Agent id new chats, goals and phases start with when nothing else names one; null keeps the first detected agent. */
  val defaultAgentId: String? = null,
  val detailsWidthDp: Int? = null,
) {
  companion object {
    const val TERMINAL_HEIGHT_DEFAULT_DP = 200
    const val TERMINAL_HEIGHT_MIN_DP = 120
    const val TERMINAL_HEIGHT_MAX_DP = 1200
    const val CHAT_WIDTH_DEFAULT_DP = 560
    const val CHAT_WIDTH_MIN_DP = 360
    const val CHAT_WIDTH_MAX_DP = 2000
    const val DETAILS_WIDTH_MIN_DP = 272

    val DEFAULT = AppSettings(
      databasePath = null,
      claudeConfigDir = null,
      historyWindow = HistoryWindow.DEFAULT,
      notifications = NotificationKind.entries.associateWith { it.defaultEnabled },
      startAtLogin = false,
      terminalHeightDp = TERMINAL_HEIGHT_DEFAULT_DP,
      chatWidthDp = CHAT_WIDTH_DEFAULT_DP,
      detailsWidthDp = null,
      trayLastTab = TrayTab.DEFAULT,
      favoriteWork = emptySet(),
      favoriteSpecs = emptySet(),
      newTaskChatLayout = NewTaskChatLayout.DEFAULT,
      qaDevice = null,
      defaultAgentId = null,
    )
  }
}

data class FavoriteWorkKey(val databaseFile: DatabaseFileIdentity, val workItemId: String)

data class FavoriteSpecKey(val projectId: String, val path: String)

fun Iterable<FavoriteSpecKey>.orderedSpecFavorites(): List<FavoriteSpecKey> =
  sortedWith(compareBy({ it.projectId }, { it.path }))

fun Iterable<FavoriteWorkKey>.orderedFavorites(normalizePath: (String) -> String): List<FavoriteWorkKey> =
  sortedWith(compareBy({ normalizePath(it.databaseFile.path) }, { it.databaseFile.fileKey }, { it.workItemId }))

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

/** How the New task screen shows the chats the user has open. */
enum class NewTaskChatLayout(val wireValue: String) {
  Tabs("tabs"),
  SideList("side_list"),
  ;

  companion object {
    val DEFAULT = Tabs
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
