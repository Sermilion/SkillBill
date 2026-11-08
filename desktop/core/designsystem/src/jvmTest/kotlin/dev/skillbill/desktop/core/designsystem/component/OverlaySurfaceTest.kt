package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class OverlaySurfaceTest {
  @Test
  fun `overlay keeps its fill and border visible`() = runComposeUiTest {
    var background = Color.Unspecified
    var border = Color.Unspecified
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        SkillBillTheme(reducedMotion = true) {
          background = SkillBillTheme.colors.bgOverlay
          border = SkillBillTheme.colors.borderStrong
          Box(Modifier.size(100.dp).sbOverlaySurface().testTag("overlay"))
        }
      }
    }

    val pixels = onNodeWithTag("overlay").captureToImage().toPixelMap()
    assertEquals(background, pixels[pixels.width / 2, pixels.height / 2])
    assertEquals(border, pixels[0, pixels.height / 2])
  }
}
