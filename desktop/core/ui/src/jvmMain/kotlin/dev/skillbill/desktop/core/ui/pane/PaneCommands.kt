package dev.skillbill.desktop.core.ui.pane

import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import dev.skillbill.desktop.core.domain.model.WorkItem

/**
 * The window's terminal pane, as the shell and other features ask for it; the terminal feature binds it. A tab opened without a
 * directory starts in the latest selection's checkout: whichever came last of a selected work item's repository root, a
 * [specProjectSelected] checkout or, while the New task screen shows, its selected chat's [chatRepositorySelected] root, else
 * the user's home. A given directory is kept even when it no longer exists, so the start fails visibly instead of silently
 * opening home.
 */
interface PaneCommands {
  val terminalOpen: StateFlow<Boolean>

  /** True while a terminal widget holds the keyboard focus; single-key shortcuts are off then. */
  val terminalFocused: StateFlow<Boolean>

  /** Opens the pane, with a new tab when it has none, or hides it; hiding keeps every shell. */
  fun toggleTerminal()

  fun newTerminalTab()

  /** Opens the pane and a new tab in [directory], or in the user's home when it is null. */
  fun openTerminal(directory: Path?)

  fun openTerminal(item: WorkItem)

  /** The spec picker's project checkout; null when the project has none, which resolves to home. */
  fun specProjectSelected(checkout: Path?)

  /**
   * The New task screen's selected chat repository while the screen shows; null once it leaves or when the chat has none, which
   * gives the start back to the work item or spec project selection beneath it.
   */
  fun chatRepositorySelected(root: Path?)

  /** Ends every shell of the window; the window calls it when it is disposed. */
  fun closeTerminals()
}
