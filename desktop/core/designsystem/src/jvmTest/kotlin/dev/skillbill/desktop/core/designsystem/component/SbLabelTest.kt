package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SbLabelTest {
  @Test
  fun `modifier applies to the whole pill so a click on it fires`() {
    var clicked = false
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          SbLabel("x", modifier = Modifier.testTag("l").clickable { clicked = true })
        }
      }
      val pill = onNodeWithTag("l")
      val pillBounds = pill.getUnclippedBoundsInRoot()
      val textBounds = onNodeWithText("x", useUnmergedTree = true).getUnclippedBoundsInRoot()
      assertTrue(pillBounds.left < textBounds.left)
      assertTrue(pillBounds.right > textBounds.right)
      pill.performMouseInput { click(Offset(1f, center.y)) }
      runOnIdle { assertTrue(clicked) }
    }
  }

  @Test
  fun `max width caps a long label and small size is 18dp high`() {
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          Column {
            SbLabel("A very long label that needs to be capped", maxWidth = 80.dp, modifier = Modifier.testTag("capped"))
            SbLabel("x", size = SbControlSize.Small, modifier = Modifier.testTag("small"))
          }
        }
      }
      val cappedBounds = onNodeWithTag("capped").getUnclippedBoundsInRoot()
      val smallBounds = onNodeWithTag("small").getUnclippedBoundsInRoot()
      assertTrue(cappedBounds.right - cappedBounds.left <= 80.dp)
      assertEquals(18.dp, smallBounds.bottom - smallBounds.top)
    }
  }
}
