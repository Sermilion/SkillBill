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

  @Test
  fun `escape belongs to a selected focused specs tree only without a layer or edit`() {
    val escape = KeyPress(ShellKey.Escape)
    assertTrue(specsPaneOwnsNavigationKey(escape, true, true, false, hasSelection = true))
    assertFalse(specsPaneOwnsNavigationKey(escape, true, true, false, hasSelection = false))
    assertFalse(specsPaneOwnsNavigationKey(escape, true, true, false, layerOpen = true, hasSelection = true))
    assertFalse(specsPaneOwnsNavigationKey(escape, true, true, false, editOpen = true, hasSelection = true))
    assertFalse(specsPaneOwnsNavigationKey(escape, false, true, false, hasSelection = true))
    assertFalse(specsPaneOwnsNavigationKey(escape, true, false, false, specsBarFocused = true, hasSelection = true))
  }

  @Test
  fun `spec navigation letters require no modifiers while shifted slash remains owned`() {
    assertTrue(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Char('j')), true, true, false))
    assertTrue(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Char('/'), shift = true), true, true, false))
    assertFalse(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Char('r'), shift = true), true, true, false))
    assertFalse(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Char('f'), ctrl = true), true, true, false))
    assertFalse(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Char('k')), true, false, false))
    assertFalse(specsPaneOwnsNavigationKey(KeyPress(ShellKey.Char('a')), true, true, false, layerOpen = true))
  }
}
