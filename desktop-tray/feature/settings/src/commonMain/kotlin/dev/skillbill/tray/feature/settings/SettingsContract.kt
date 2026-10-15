package dev.skillbill.tray.feature.settings

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.ui.Feedback

/** Whether the persisted preferences can be edited. The controls only exist once they have been read. */
sealed interface SettingsReadiness {
  data object Loading : SettingsReadiness
  data object Ready : SettingsReadiness

  /** The preferences could not be read, so nothing is shown as a value that might overwrite them. */
  data class Unreadable(val problem: Problem) : SettingsReadiness
}

enum class PathError {
  /** The path has text but only whitespace; blank means "use the runtime default" only when it is empty. */
  PathBlank,
}

data class SettingsUiState(
  val readiness: SettingsReadiness = SettingsReadiness.Loading,
  /** The preferences as last persisted. */
  val settings: AppSettings = AppSettings(),
  /** The database path being typed; it is only written by [SettingsAction.SavePath]. */
  val draftPath: String = "",
  val pathError: PathError? = null,
  val savingPath: Boolean = false,
  /** The database the board is reading right now, which a blank path leaves to the runtime; null when none is open. */
  val effectiveDatabasePath: String? = null,
  val pendingFeedback: List<Feedback> = emptyList(),
)

sealed interface SettingsAction {
  data class PathChanged(val text: String) : SettingsAction
  data object SavePath : SettingsAction
  data class RetentionChosen(val retention: Retention) : SettingsAction
  data class NotificationToggled(val key: String, val enabled: Boolean) : SettingsAction
  data class StartAtLoginChanged(val enabled: Boolean) : SettingsAction
  data class AcknowledgeFeedback(val id: Long) : SettingsAction
}

sealed interface SettingsEffect {
  /** The database path was written; a brief confirmation, so missing it costs nothing. */
  data object Saved : SettingsEffect
}
