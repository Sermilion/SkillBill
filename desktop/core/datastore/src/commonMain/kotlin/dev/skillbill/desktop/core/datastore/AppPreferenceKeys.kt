package dev.skillbill.desktop.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.skillbill.desktop.core.domain.settings.NotificationKind

/** Every preference name the app stores. No other file spells a key. */
internal object AppPreferenceKeys {
  /** Relative to the user's home; `PreferenceDataStoreFactory` requires the `.preferences_pb` extension. */
  const val FILE_PATH = ".skill-bill/desktop/settings.preferences_pb"

  val DATABASE_PATH = stringPreferencesKey("database_path")
  val CLAUDE_CONFIG_DIR = stringPreferencesKey("claude_config_dir")
  val HISTORY_RETENTION = stringPreferencesKey("history_retention")
  val START_AT_LOGIN = booleanPreferencesKey("start_at_login")
  val TERMINAL_HEIGHT_DP = intPreferencesKey("terminal_height_dp")
  val TRAY_LAST_TAB = stringPreferencesKey("tray_last_tab")

  private val NOTIFY_BLOCKED = booleanPreferencesKey("notify_blocked")
  private val NOTIFY_FAILED = booleanPreferencesKey("notify_failed")
  private val NOTIFY_GOAL_COMPLETED = booleanPreferencesKey("notify_goal_completed")
  private val NOTIFY_PAUSE_TOOK_EFFECT = booleanPreferencesKey("notify_pause_took_effect")
  private val NOTIFY_STANDALONE_PHASE_FINISHED = booleanPreferencesKey("notify_standalone_phase_finished")
  private val NOTIFY_QA_INCOMPLETE = booleanPreferencesKey("notify_qa_incomplete")

  fun notificationKey(kind: NotificationKind) = when (kind) {
    NotificationKind.Blocked -> NOTIFY_BLOCKED
    NotificationKind.Failed -> NOTIFY_FAILED
    NotificationKind.GoalCompleted -> NOTIFY_GOAL_COMPLETED
    NotificationKind.PauseTookEffect -> NOTIFY_PAUSE_TOOK_EFFECT
    NotificationKind.StandalonePhaseFinished -> NOTIFY_STANDALONE_PHASE_FINISHED
    NotificationKind.QaIncomplete -> NOTIFY_QA_INCOMPLETE
  }
}
