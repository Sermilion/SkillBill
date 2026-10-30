package dev.skillbill.desktop.app.keyboard

import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

/** Every terminal trigger in the shell: the sidebar toggle, Ctrl+Backquote, Ctrl+Shift+Backquote and the menu's terminal commands. */
@Inject
@SingleIn(WindowScope::class)
class TerminalTriggers(private val commands: PaneCommands) {
  val open: Boolean get() = commands.terminalOpen.value

  fun toggle() = commands.toggleTerminal()

  fun newTab() = commands.newTerminalTab()

  fun openIn(repositoryRoot: String) = commands.openTerminal(Path.of(repositoryRoot))

  /** Ends the window's shells; the window calls it on disposal. */
  fun close() = commands.closeTerminals()
}
