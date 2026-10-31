package dev.skillbill.desktop.app.keyboard

import dev.skillbill.desktop.app.command.CommandMenuState
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.pane.ChatCommands
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Runs what [ShellKeymap] resolves. [onKey] is true only when a command consumed the key, so every other key reaches the focus. */
@Inject
@SingleIn(WindowScope::class)
class ShellKeyboard(
  private val navigator: WindowNavigator,
  private val panes: ShellPanes,
  private val input: ShellInput,
  private val menu: CommandMenuState,
  private val terminal: TerminalTriggers,
  private val chat: ChatCommands,
  private val selection: SelectionHolder,
) {
  private val keymap = ShellKeymap()

  /** Set by the window from its width. */
  var drawerMode = false

  /** Set by the window to its focus manager's clear. */
  var clearFocus: () -> Unit = {}

  fun onKey(press: KeyPress): Boolean {
    val context = KeyContext(
      paletteOpen = menu.open,
      sheetOpen = panes.shortcutsOpen,
      layerOpen = input.layerOpen,
      editOpen = input.editOpen,
      textInputFocused = input.textInputFocused,
      terminalFocused = input.terminalFocused,
      drawerMode = drawerMode,
    )
    return run(keymap.resolve(press, context) ?: return false)
  }

  private fun run(command: ShellCommand): Boolean {
    when (command) {
      ShellCommand.TogglePalette -> menu.toggle()
      ShellCommand.ToggleTerminal -> terminal.toggle()
      ShellCommand.NewTerminalTab -> terminal.newTab()
      ShellCommand.Escape -> return escape()
      ShellCommand.SaveEdit -> return input.saveTopEdit()
      ShellCommand.ToggleDetailsPane -> panes.detailsPaneOpen = !panes.detailsPaneOpen
      ShellCommand.Consumed -> Unit
      ShellCommand.ShowShortcuts -> panes.shortcutsOpen = true
      ShellCommand.ToggleDrawer -> panes.drawerOpen = !panes.drawerOpen
      ShellCommand.ToggleSpecs -> panes.specsOpen = !panes.specsOpen
      ShellCommand.ToggleChat -> if (navigator.top.route !is SettingsRoute) chat.toggleChat(selection.item.value)
      is ShellCommand.Navigate -> navigator.navigate(command.route)
      is ShellCommand.Screen -> return input.dispatchScreenKey(navigator.top.contentKey, command.key)
    }
    return true
  }

  /** Esc closes the top layer first, then cancels an open edit, then closes the drawer or leaves a focused field, then leaves detail or settings. */
  fun escape(): Boolean = when {
    panes.shortcutsOpen -> { panes.shortcutsOpen = false; true }
    input.dismissTopLayer() -> true
    input.cancelTopEdit() -> true
    drawerMode && panes.drawerOpen -> { panes.drawerOpen = false; true }
    input.textInputFocused -> { clearFocus(); true }
    navigator.top.route is DetailRoute || navigator.top.route is SettingsRoute -> navigator.pop()
    else -> false
  }
}
