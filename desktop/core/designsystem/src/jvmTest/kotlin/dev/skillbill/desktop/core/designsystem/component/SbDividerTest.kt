package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Box
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
class SbDividerTest {
  @Test
  fun `horizontal and vertical dividers keep a one dp stroke across their full length`() {
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          Box(Modifier.size(200.dp, 80.dp)) { SbDivider(Modifier.testTag("h")) }
          Box(Modifier.size(200.dp, 80.dp)) { SbDivider(Modifier.testTag("v"), vertical = true) }
        }
      }
      val horizontal = onNodeWithTag("h").getUnclippedBoundsInRoot()
      val vertical = onNodeWithTag("v").getUnclippedBoundsInRoot()
      assertEquals(200.dp, horizontal.right - horizontal.left)
      assertEquals(1.dp, horizontal.bottom - horizontal.top)
      assertEquals(1.dp, vertical.right - vertical.left)
      assertEquals(80.dp, vertical.bottom - vertical.top)
    }
  }
}
