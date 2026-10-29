package dev.skillbill.desktop.app.keyboard

import dev.skillbill.desktop.core.ui.input.ScreenKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class ShellKeymapTest {
  private fun key(char: Char, ctrl: Boolean = false, meta: Boolean = false) = KeyPress(ShellKey.Char(char), ctrl = ctrl, meta = meta)

  @Test
  fun `a single-key shortcut is ignored while a text field has focus and runs otherwise`() {
    val keymap = ShellKeymap()
    assertNull(keymap.resolve(key('p'), KeyContext(textInputFocused = true)))
    assertNull(keymap.resolve(key('p'), KeyContext(terminalFocused = true)))
    assertEquals(ShellCommand.Screen(ScreenKey.PauseAfterSubtask), keymap.resolve(key('p'), KeyContext()))
  }

  @Test
  fun `a chord expires after its timeout`() {
    val time = TestTimeSource()
    val keymap = ShellKeymap(time)
    assertEquals(ShellCommand.Consumed, keymap.resolve(key('g'), KeyContext()))
    time += 1300.milliseconds
    assertNull(keymap.resolve(key('h'), KeyContext()))
  }

  @Test
  fun `the command menu shortcut works while a text field has focus`() {
    assertEquals(ShellCommand.TogglePalette, ShellKeymap().resolve(key('k', meta = true), KeyContext(textInputFocused = true)))
  }
}
