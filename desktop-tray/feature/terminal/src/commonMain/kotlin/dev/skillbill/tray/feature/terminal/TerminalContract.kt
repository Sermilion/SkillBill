package dev.skillbill.tray.feature.terminal

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.WorkIdentity

/** The bottom terminal pane: whether it is open, how tall, and the directory a session opened now would start in. */
data class TerminalUiState(
  val paneOpen: Boolean = false,
  val heightDp: Int = AppSettings.TERMINAL_PANE_DEFAULT_HEIGHT_DP,
  /**
   * The repository root of the latest selection, a sidebar item or a spec; null when nothing is selected, the selected work could
   * not be read, or the spec's project has no checkout on this machine.
   */
  val workingDirectory: String? = null,
  /** The sidebar selection [workingDirectory] is settled for; it differs from the current selection while that read is in flight. */
  val resolvedFor: WorkIdentity? = null,
)

sealed interface TerminalAction {
  data object TogglePane : TerminalAction

  data object ClosePane : TerminalAction

  data class ResizeTo(val heightDp: Int) : TerminalAction

  data class SelectedWorkChanged(val identity: WorkIdentity?) : TerminalAction

  /** A spec picked in the spec pane; [repositoryRoot] is its project's checkout, null when it has none on this machine. */
  data class SpecSelected(val repositoryRoot: String?) : TerminalAction
}
