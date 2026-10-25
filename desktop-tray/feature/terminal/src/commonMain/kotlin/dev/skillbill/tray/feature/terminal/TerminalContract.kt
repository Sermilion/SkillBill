package dev.skillbill.tray.feature.terminal

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.WorkIdentity

/** The bottom terminal pane: whether it is open, how tall, and the directory a session opened now would start in. */
data class TerminalUiState(
  val paneOpen: Boolean = false,
  val heightDp: Int = AppSettings.TERMINAL_PANE_DEFAULT_HEIGHT_DP,
  /** The selected work's repository root; null when nothing is selected or it could not be read. */
  val workingDirectory: String? = null,
  /** The selection [workingDirectory] was resolved for; it differs from the current selection while that read is in flight. */
  val resolvedFor: WorkIdentity? = null,
)

sealed interface TerminalAction {
  data object TogglePane : TerminalAction

  data object ClosePane : TerminalAction

  data class ResizeTo(val heightDp: Int) : TerminalAction

  data class SelectedWorkChanged(val identity: WorkIdentity?) : TerminalAction
}
