package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.shell.DockedPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WorkAreaTest {
  @Test
  fun `a docked pane sits beside a 300dp main column in a narrow drawer-mode work area`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          Box(Modifier.width(560.dp).height(400.dp)) {
            WorkArea(
              drawerMode = true,
              right = { DockedPane("Chat", 560, onResize = {}, onReset = {}, onDismiss = {}) { Box(Modifier.fillMaxSize().testTag("pane")) } },
              bottom = null,
              rightWidth = null,
            ) { Box(Modifier.fillMaxSize().testTag("main")) }
          }
        }
      }
    }
    val main = onNodeWithTag("main").getUnclippedBoundsInRoot()
    val pane = onNodeWithTag("pane").getUnclippedBoundsInRoot()
    assertEquals(300.dp, main.width)
    assertTrue(pane.left >= main.right)
  }
}
