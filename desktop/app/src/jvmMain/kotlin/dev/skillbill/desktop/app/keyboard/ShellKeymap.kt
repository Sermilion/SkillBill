package dev.skillbill.desktop.app.keyboard

import dev.skillbill.desktop.core.ui.input.ScreenKey
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How long `G` waits for its second key, as in the prototype. */
val CHORD_TIMEOUT = 1200.milliseconds

sealed interface ShellKey {
  data class Char(val char: kotlin.Char) : ShellKey

  data object Enter : ShellKey

  data object Escape : ShellKey

  data object Up : ShellKey

  data object Down : ShellKey

  data object Grave : ShellKey
}

data class KeyPress(val key: ShellKey, val ctrl: Boolean = false, val meta: Boolean = false, val shift: Boolean = false, val alt: Boolean = false)

data class KeyContext(
  val paletteOpen: Boolean = false,
  val sheetOpen: Boolean = false,
  val layerOpen: Boolean = false,
  val editOpen: Boolean = false,
  val textInputFocused: Boolean = false,
  val terminalFocused: Boolean = false,
  val drawerMode: Boolean = false,
)

sealed interface ShellCommand {
  data object TogglePalette : ShellCommand

  data object ToggleTerminal : ShellCommand

  data object NewTerminalTab : ShellCommand

  data object Escape : ShellCommand

  data object SaveEdit : ShellCommand

  data object ToggleDetailsPane : ShellCommand

  /** A key the shell swallows without acting: an armed chord, or a chord's unknown second key. */
  data object Consumed : ShellCommand

  data object ShowShortcuts : ShellCommand

  data object ToggleDrawer : ShellCommand

  data object ToggleSpecs : ShellCommand

  data class Navigate(val route: AppRoute) : ShellCommand

  data class Screen(val key: ScreenKey) : ShellCommand
}

/** The window's key map. It decides what a key means and runs nothing; [ShellKeyboard] runs the result. */
class ShellKeymap(private val timeSource: TimeSource = TimeSource.Monotonic) {
  private var chord: TimeMark? = null

  fun resolve(press: KeyPress, context: KeyContext): ShellCommand? {
    val mod = press.ctrl || press.meta
    val char = (press.key as? ShellKey.Char)?.char?.lowercaseChar()
    if (mod && char == 'k') return ShellCommand.TogglePalette
    if (context.paletteOpen) return null
    if (press.ctrl && press.key == ShellKey.Grave) return if (press.shift) ShellCommand.NewTerminalTab else ShellCommand.ToggleTerminal
    if (press.key == ShellKey.Escape) {
      chord = null
      return ShellCommand.Escape
    }
    if (mod && press.key == ShellKey.Enter) return if (context.editOpen) ShellCommand.SaveEdit else null
    if (mod && char == 'i') return if (context.layerOpen) null else ShellCommand.ToggleDetailsPane
    if (mod || press.alt) return null
    if (context.layerOpen || context.sheetOpen || context.textInputFocused || context.terminalFocused) {
      chord = null
      return null
    }
    val armed = chord
    chord = null
    if (armed != null && armed.elapsedNow() <= CHORD_TIMEOUT) {
      return when (char) {
        'w' -> ShellCommand.Navigate(WorkRoute(null))
        'h' -> ShellCommand.Navigate(HistoryRoute)
        's' -> ShellCommand.ToggleSpecs
        ',' -> ShellCommand.Navigate(SettingsRoute(SettingsPageId.App))
        else -> ShellCommand.Consumed
      }
    }
    return when {
      char == 'g' -> {
        chord = timeSource.markNow()
        ShellCommand.Consumed
      }
      char == '?' -> ShellCommand.ShowShortcuts
      char == '[' -> if (context.drawerMode) ShellCommand.ToggleDrawer else null
      char == '/' -> ShellCommand.Screen(ScreenKey.FocusFilter)
      char == 'j' || press.key == ShellKey.Down -> ShellCommand.Screen(ScreenKey.Next)
      char == 'k' || press.key == ShellKey.Up -> ShellCommand.Screen(ScreenKey.Previous)
      press.key == ShellKey.Enter -> ShellCommand.Screen(ScreenKey.Open)
      char == 'p' -> ShellCommand.Screen(if (press.shift) ScreenKey.PauseNow else ScreenKey.PauseAfterSubtask)
      char == 'r' -> ShellCommand.Screen(ScreenKey.Resume)
      else -> null
    }
  }
}
