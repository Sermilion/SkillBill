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
  /** The window's terminal tabs in creation order. Window-lifetime state, never persisted. */
  val tabs: List<TerminalTab> = emptyList(),
  /** The tab whose shell the pane shows; null while [tabs] is empty. */
  val activeTabId: Long? = null,
)

/**
 * One shell in the pane. [id] is minted by the host so it is never reused; [label] is computed from the live tab list, so it
 * can change when another tab opens or closes.
 */
data class TerminalTab(val id: Long, val directory: String, val isHome: Boolean, val label: String)

sealed interface TerminalAction {
  data object TogglePane : TerminalAction

  data object ClosePane : TerminalAction

  data class ResizeTo(val heightDp: Int) : TerminalAction

  data class SelectedWorkChanged(val identity: WorkIdentity?) : TerminalAction

  /** A spec picked in the spec pane; [repositoryRoot] is its project's checkout, null when it has none on this machine. */
  data class SpecSelected(val repositoryRoot: String?) : TerminalAction

  /** Appends a tab and makes it active, even when another tab already has [directory]. */
  data class NewTab(val id: Long, val directory: String, val isHome: Boolean) : TerminalAction

  /** Makes an existing tab active; an unknown id changes nothing. */
  data class SelectTab(val id: Long) : TerminalAction

  /** The host's report that the pane opened in [directory]; only the first one per opening applies. */
  data class PaneOpened(val id: Long, val directory: String, val isHome: Boolean) : TerminalAction

  data class CloseTab(val id: Long) : TerminalAction

  /** Shells that exited on their own; ids with no tab are ignored. */
  data class TabsEnded(val ids: Set<Long>) : TerminalAction
}
