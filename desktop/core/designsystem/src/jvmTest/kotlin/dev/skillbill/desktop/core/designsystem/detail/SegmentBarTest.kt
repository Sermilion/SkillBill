package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SegmentBarTest {
  @Test
  fun `positive weights set segment widths and zero weight consumes no space`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        SegmentBar(
          listOf(BarSegment(3f, Color.Red), BarSegment(0f, Color.Blue), BarSegment(1f, Color.Green)),
          "Progress",
          Modifier.width(100.dp),
        )
      }
    }

    val bar = onNodeWithContentDescription("Progress")
    bar.assertHeightIsEqualTo(5.dp)
    val pixels = bar.captureToImage().toPixelMap()
    assertEquals(Color.Red, pixels[pixels.width * 30 / 100, pixels.height / 2])
    assertEquals(Color.Red, pixels[pixels.width * 70 / 100, pixels.height / 2])
    assertEquals(Color.Green, pixels[pixels.width * 90 / 100, pixels.height / 2])
  }

  @Test
  fun `nonpositive segments leave the described track visible`() = runComposeUiTest {
    var trackColor = Color.Unspecified
    setContent {
      SkillBillTheme(reducedMotion = true) {
        trackColor = SkillBillTheme.colors.borderSubtle
        SegmentBar(
          listOf(BarSegment(0f, Color.Red), BarSegment(-1f, Color.Blue)),
          "Empty progress",
          Modifier.width(100.dp),
        )
      }
    }

    val bar = onNodeWithContentDescription("Empty progress")
    bar.assertHeightIsEqualTo(5.dp)
    val pixels = bar.captureToImage().toPixelMap()
    assertEquals(trackColor, pixels[pixels.width / 2, pixels.height / 2])
  }
}
