package dev.skillbill.desktop.app.keyboard

import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Every terminal trigger in the shell: the sidebar toggle, Ctrl+Backquote, Ctrl+Shift+Backquote and the menu's terminal commands.
 * The terminal feature re-points these bodies at its own pane commands; until then they only open or close the bottom pane.
 */
@Inject
@SingleIn(WindowScope::class)
class TerminalTriggers(private val panes: ShellPanes) {
  val open: Boolean get() = panes.terminalOpen

  fun toggle() {
    panes.terminalOpen = !panes.terminalOpen
  }

  fun newTab() {
    panes.terminalOpen = true
  }

  /** The checkout the latest "Open terminal in" asked for; the terminal pane opens its tab there. */
  var requestedDirectory: String? = null
    private set

  fun openIn(repositoryRoot: String) {
    requestedDirectory = repositoryRoot
    panes.terminalOpen = true
  }
}
