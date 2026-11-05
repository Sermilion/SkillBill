package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SbPopoverTest {
  private val window = IntSize(1000, 800)
  private val panel = IntSize(100, 200)

  private fun top(placement: SbPopoverPlacement, anchorTop: Int) =
    PopoverPositionProvider(IntOffset.Zero, 4, 8, placement)
      .calculatePosition(IntRect(50, anchorTop, 150, anchorTop + 20), window, LayoutDirection.Ltr, panel).y

  private fun contentWidth(width: Dp, maxWidth: Dp?): Dp {
    var measured = 0.dp
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          val content: @Composable ColumnScope.() -> Unit = {
            Box(Modifier.fillMaxWidth().height(10.dp).testTag("content"))
          }
          if (maxWidth == null) SbPopover(expanded = true, onDismissRequest = {}, width = width, content = content)
          else SbPopover(expanded = true, onDismissRequest = {}, width = width, maxWidth = maxWidth, content = content)
        }
      }
      waitForIdle()
      val bounds = onNodeWithTag("content").getUnclippedBoundsInRoot()
      measured = bounds.right - bounds.left
    }
    return measured
  }

  @Test
  fun `a default popover stays capped at 340dp`() {
    assertEquals(338.dp, contentWidth(500.dp, null))
  }

  @Test
  fun `a raised maxWidth lets the panel reach it`() {
    assertEquals(618.dp, contentWidth(620.dp, 620.dp))
  }

  @Test
  fun `Above opens over the anchor and flips below only when it does not fit`() {
    assertEquals(300 - 200 - 4, top(SbPopoverPlacement.Above, 300))
    assertEquals(100 + 20 + 4, top(SbPopoverPlacement.Above, 100))
  }

  @Test
  fun `Auto still prefers below`() {
    assertEquals(300 + 20 + 4, top(SbPopoverPlacement.Auto, 300))
  }
}
