package dev.skillbill.desktop.app.shell

import dev.skillbill.desktop.app.keyboard.KeyPress
import dev.skillbill.desktop.app.keyboard.ShellKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecsPaneKeyOwnershipTest {
  @Test
  fun `open but unfocused specs pane leaves work navigation with the main screen`() {
    assertFalse(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Down), specsOpen = true, specsTreeFocused = false, subtaskListFocused = false))
  }

  @Test
  fun `focused specs tree and detail subtask list own their navigation keys`() {
    assertTrue(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Down), specsOpen = true, specsTreeFocused = true, subtaskListFocused = false))
    assertTrue(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Enter), specsOpen = false, specsTreeFocused = false, subtaskListFocused = true))
  }

  @Test
  fun `modified enter remains available to shell save shortcuts`() {
    assertFalse(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Enter, ctrl = true), specsOpen = true, specsTreeFocused = true, subtaskListFocused = false))
  }
}
