package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.shell.DockedPane
import dev.skillbill.desktop.core.ui.shell.LocalRightPaneBounds
import dev.skillbill.desktop.core.ui.shell.RightPaneBounds
import dev.skillbill.desktop.core.ui.shell.dockedPaneWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DockedPaneTest {
  @Test
  fun `Esc reaches the pane only while focus is inside it`() = runComposeUiTest {
    val input = ShellInput()
    val inside = FocusRequester()
    val outside = FocusRequester()
    var dismissed = 0
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides input) {
          Row {
            Box(Modifier.size(40.dp).focusRequester(outside).focusable())
            DockedPane("Chat", 560, onResize = {}, onDismiss = { dismissed++ }) {
              Box(Modifier.size(40.dp).focusRequester(inside).focusable())
            }
          }
        }
      }
    }
    runOnIdle { inside.requestFocus() }
    waitForIdle()
    runOnIdle {
      assertTrue(input.layerOpen)
      assertTrue(input.dismissTopLayer())
      assertEquals(1, dismissed)
    }
    runOnIdle { outside.requestFocus() }
    waitForIdle()
    runOnIdle {
      assertFalse(input.layerOpen)
      assertFalse(input.dismissTopLayer())
      assertEquals(1, dismissed)
    }
  }

  @Test
  fun `docked width stays between the 360dp floor and the main column floor and a key step starts from the rendered width`() =
    runComposeUiTest {
      assertEquals(580.dp, dockedPaneWidth(2000.dp, 1000.dp))
      assertEquals(360.dp, dockedPaneWidth(200.dp, 1000.dp))
      assertEquals(360.dp, dockedPaneWidth(500.dp, 600.dp))
      val resized = mutableListOf<Int>()
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(
            LocalShellInput provides ShellInput(),
            LocalRightPaneBounds provides RightPaneBounds(cover = false, workAreaWidth = 1000.dp),
          ) {
            DockedPane("Chat", 2000, onResize = { resized += it }, onDismiss = {}) {}
          }
        }
      }
      val grip = onNodeWithContentDescription("Resize chat panel")
      grip.performSemanticsAction(SemanticsActions.RequestFocus)
      waitForIdle()
      grip.performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
      runOnIdle { assertEquals(listOf(580), resized) }
    }
}
