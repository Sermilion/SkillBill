package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SkeletonBlockTest {
  @Test
  fun `caller size and theme fill are preserved`() = runComposeUiTest {
    var expectedColor = Color.Unspecified
    setContent {
      SkillBillTheme(reducedMotion = true) {
        expectedColor = SkillBillTheme.colors.bgActive
        SkeletonBlock(Modifier.size(40.dp, 8.dp).testTag("s"))
      }
    }

    onNodeWithTag("s").assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(8.dp)
    val pixels = onNodeWithTag("s").captureToImage().toPixelMap()
    assertEquals(expectedColor, pixels[pixels.width / 2, pixels.height / 2])
  }
}
