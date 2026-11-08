package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SbBoxTest {
  @Test
  fun `fill stays behind content and border draws on top`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        SbBox(Modifier.testTag("box").size(40.dp), fill = Color.Red, borderColor = Color.Blue) {
          Box(Modifier.fillMaxSize().padding(8.dp).background(Color.Green))
        }
      }
    }

    val pixels = onNodeWithTag("box").captureToImage().toPixelMap()
    val content = pixels[pixels.width / 2, pixels.height / 2]
    assertTrue(content.green > 0.5f && content.red < 0.5f && content.blue < 0.5f && content.alpha > 0.5f)
    val fill = pixels[pixels.width / 10, pixels.height / 2]
    assertTrue(fill.red > 0.5f && fill.green < 0.5f && fill.blue < 0.5f && fill.alpha > 0.5f)
    val border = pixels[0, pixels.height / 2]
    assertTrue(border.blue > 0.5f && border.red < 0.5f && border.green < 0.5f && border.alpha > 0.5f)
  }

  @Test
  fun `header stays above content inside the box`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        SbBox(Modifier.testTag("box").size(120.dp, 80.dp), header = {
          Box(Modifier.testTag("header").fillMaxWidth().height(20.dp))
        }) {
          Box(Modifier.testTag("content").fillMaxWidth().height(30.dp))
        }
      }
    }

    val box = onNodeWithTag("box").fetchSemanticsNode().boundsInRoot
    val header = onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
    val content = onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
    assertTrue(header.bottom <= content.top)
    assertTrue(header.left >= box.left && header.top >= box.top && header.right <= box.right && header.bottom <= box.bottom)
    assertTrue(content.left >= box.left && content.top >= box.top && content.right <= box.right && content.bottom <= box.bottom)
  }
}
