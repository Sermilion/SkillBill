package dev.skillbill.desktop.core.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.LocalEntryKey
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The keys the shell hands to the active screen; each screen decides what they do. */
enum class ScreenKey { FocusFilter, Next, Previous, Open, PauseAfterSubtask, PauseNow, Resume }

/**
 * What the window's key handler needs to know about the screens: which inputs have focus, which menus, dialogs and edits are open,
 * and which screen handles the screen keys. Everything here runs on the UI thread.
 */
@Inject
@SingleIn(WindowScope::class)
class ShellInput {
  private val focusedSources = mutableSetOf<String>()
  private val layers = mutableListOf<Layer>()
  private val edits = mutableListOf<Edit>()
  private val screenKeys = mutableMapOf<String, (ScreenKey) -> Boolean>()
  private var textFieldSources = 0

  val textInputFocused: Boolean get() = focusedSources.any { it != TERMINAL_SOURCE }
  val terminalFocused: Boolean get() = TERMINAL_SOURCE in focusedSources
  val specsTreeFocused: Boolean get() = SPECS_TREE_SOURCE in focusedSources
  val layerOpen: Boolean get() = layers.isNotEmpty()
  val editOpen: Boolean get() = edits.isNotEmpty()

  /** Reports a focus holder other than a Compose text field; the terminal reports itself as [TERMINAL_SOURCE]. */
  fun setFocusSource(source: String, focused: Boolean) {
    if (focused) focusedSources += source else focusedSources -= source
  }

  fun newTextFieldSource(): String = "text-field:${++textFieldSources}"

  fun addLayer(layer: Layer) { layers += layer }

  fun removeLayer(layer: Layer) { layers -= layer }

  /** Dismisses the newest open layer; false when none is open. */
  fun dismissTopLayer(): Boolean = layers.lastOrNull()?.let { it.onDismiss(); true } ?: false

  fun addEdit(edit: Edit) { edits += edit }

  fun removeEdit(edit: Edit) { edits -= edit }

  fun cancelTopEdit(): Boolean = edits.lastOrNull()?.let { it.onCancel(); true } ?: false

  fun saveTopEdit(): Boolean = edits.lastOrNull()?.let { it.onSave(); true } ?: false

  fun registerScreenKeys(entryKey: String, handler: (ScreenKey) -> Boolean) { screenKeys[entryKey] = handler }

  fun unregisterScreenKeys(entryKey: String, handler: (ScreenKey) -> Boolean) { screenKeys.remove(entryKey, handler) }

  /** Hands [key] to the screen registered under the top entry's [entryKey] only; true when that screen consumed it. */
  fun dispatchScreenKey(entryKey: String?, key: ScreenKey): Boolean = entryKey?.let(screenKeys::get)?.invoke(key) ?: false

  class Layer(val onDismiss: () -> Unit)

  class Edit(val onCancel: () -> Unit, val onSave: () -> Unit)

  companion object {
    const val TERMINAL_SOURCE = "terminal"
    const val SPECS_TREE_SOURCE = "specs-tree"
  }
}

val LocalShellInput = staticCompositionLocalOf<ShellInput> { error("LocalShellInput is provided by the window.") }

/** Gates single-key shortcuts while this text field has focus. Every text field needs it; a field without it lets single keys through. */
@Composable
fun Modifier.gatesShortcutsWhileFocused(): Modifier {
  val input = LocalShellInput.current
  val source = remember(input) { input.newTextFieldSource() }
  DisposableEffect(input, source) { onDispose { input.setFocusSource(source, false) } }
  return onFocusChanged { input.setFocusSource(source, it.hasFocus) }
}

/**
 * Compose alongside every open menu, popover or dialog: while it is composed, single keys are ignored and Esc calls [onDismiss]
 * before anything else. A menu without it lets single keys through.
 */
@Composable
fun ShellLayer(onDismiss: () -> Unit) {
  val input = LocalShellInput.current
  val current by rememberUpdatedState(onDismiss)
  DisposableEffect(input) {
    val layer = ShellInput.Layer { current() }
    input.addLayer(layer)
    onDispose { input.removeLayer(layer) }
  }
}

/** Compose alongside an open config edit or inline confirmation: Esc calls [onCancel] and Cmd/Ctrl+Enter calls [onSave]. */
@Composable
fun ShellEdit(onCancel: () -> Unit, onSave: () -> Unit) {
  val input = LocalShellInput.current
  val cancel by rememberUpdatedState(onCancel)
  val save by rememberUpdatedState(onSave)
  DisposableEffect(input) {
    val edit = ShellInput.Edit(onCancel = { cancel() }, onSave = { save() })
    input.addEdit(edit)
    onDispose { input.removeEdit(edit) }
  }
}

/** Registers this screen's handler for the screen keys; the shell calls it only while this entry is on top. */
@Composable
fun ScreenKeyHandler(handler: (ScreenKey) -> Boolean) {
  val input = LocalShellInput.current
  val entryKey = LocalEntryKey.current
  val current by rememberUpdatedState(handler)
  DisposableEffect(input, entryKey) {
    val registered: (ScreenKey) -> Boolean = { current(it) }
    input.registerScreenKeys(entryKey, registered)
    onDispose { input.unregisterScreenKeys(entryKey, registered) }
  }
}
