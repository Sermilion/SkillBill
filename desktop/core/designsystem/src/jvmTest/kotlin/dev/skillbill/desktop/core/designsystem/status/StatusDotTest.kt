package dev.skillbill.desktop.core.designsystem.status

import androidx.compose.foundation.layout.Column
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
class StatusDotTest {
  @Test
  fun `filled and hollow dots are 6dp`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          StatusDot(Color.Red, Modifier.testTag("filled"))
          StatusDot(Color.Red, Modifier.testTag("hollow"), hollow = true)
        }
      }
    }

    onNodeWithTag("filled").assertWidthIsEqualTo(6.dp).assertHeightIsEqualTo(6.dp)
    onNodeWithTag("hollow").assertWidthIsEqualTo(6.dp).assertHeightIsEqualTo(6.dp)
  }

  @Test
  fun `a pulsing dot stays at full alpha under reduced motion`() = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        StatusDot(Color.Red, Modifier.testTag("dot"), pulse = true)
      }
    }

    mainClock.advanceTimeBy(400)
    val centerAlpha = onNodeWithTag("dot").captureToImage().toPixelMap()[3, 3].alpha
    assertEquals(1f, centerAlpha)
  }
}
