package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ContentPanelTest {
  @Test
  fun `content stays inset from the panel border`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ContentPanel(Modifier.size(200.dp).testTag("panel")) {
          Box(Modifier.fillMaxSize().testTag("child"))
        }
      }
    }
    val panel = onNodeWithTag("panel").getUnclippedBoundsInRoot()
    val child = onNodeWithTag("child").getUnclippedBoundsInRoot()
    assertEquals(1.dp, child.left - panel.left)
    assertEquals(1.dp, child.top - panel.top)
    assertEquals(1.dp, panel.right - child.right)
    assertEquals(1.dp, panel.bottom - child.bottom)
  }
}
