package dev.skillbill.desktop.feature.settings

import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.ui.feedback.ToastMessage

data class SettingsUiState(
  val ready: Boolean = false,
  val settings: AppSettings = AppSettings.DEFAULT,
  val draftPath: String = "",
  val pathEdited: Boolean = false,
  val pathError: PathError? = null,
  val savingPath: Boolean = false,
  val currentDatabase: String? = null,
  val claudeDiscovery: ClaudeDirectoryDiscovery = ClaudeDirectoryDiscovery.Unavailable,
  val loginPending: Boolean = false,
  val loginError: String? = null,
  val writeError: String? = null,
)

sealed interface ClaudeDirectoryDiscovery {
  data class Pending(val sessionId: Long) : ClaudeDirectoryDiscovery

  data object Unavailable : ClaudeDirectoryDiscovery

  data class Success(val sessionId: Long, val directories: List<String>) : ClaudeDirectoryDiscovery

  data class Failed(val sessionId: Long, val problem: ReadProblem) : ClaudeDirectoryDiscovery
}

enum class PathError { OnlySpaces, WriteFailed }

sealed interface SettingsAction {
  data class PathChanged(val text: String) : SettingsAction
  data object SavePath : SettingsAction
  data class ClaudeDirectoryChosen(val directory: String) : SettingsAction
  data class RetentionChosen(val window: HistoryWindow) : SettingsAction
  data class NotificationToggled(val kind: NotificationKind, val enabled: Boolean) : SettingsAction
  data class StartAtLoginChanged(val enabled: Boolean) : SettingsAction
}

sealed interface SettingsEffect {
  data class ShowToast(val message: ToastMessage) : SettingsEffect
  data object ClearFocus : SettingsEffect
}
