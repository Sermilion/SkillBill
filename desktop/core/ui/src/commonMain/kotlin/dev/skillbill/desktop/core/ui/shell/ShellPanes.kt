package dev.skillbill.desktop.core.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.ui.di.WindowScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Which of the window's panes and sheets are open. The Specs pane reads [specsOpen]; it never changes the main panel. */
@Inject
@SingleIn(WindowScope::class)
class ShellPanes {
  private var specs by mutableStateOf(false)
  private var chat by mutableStateOf(false)

  /** Opening Specs closes the chat pane. */
  var specsOpen: Boolean
    get() = specs
    set(value) {
      specs = value
      if (value) chat = false
    }

  /** Mirrors the chat workspace's pane state for the shell's layout; only the chat workspace writes it. Opening it closes Specs. */
  var chatOpen: Boolean
    get() = chat
    set(value) {
      chat = value
      if (value) specs = false
    }
  var detailsPaneOpen by mutableStateOf(false)
  var drawerOpen by mutableStateOf(false)
  var shortcutsOpen by mutableStateOf(false)

  /** Mirrors the terminal feature's pane state for the shell's layout; only the terminal workspace writes it. */
  var terminalOpen by mutableStateOf(false)
}
