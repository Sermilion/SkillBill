package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PeekActivityTextTest {
  @Test
  fun `validation activity stays compact and discloses the complete failure`() = runComposeUiTest {
    val short = "Started validation"
    val failure = "Runtime validation failed.\n" +
      "Passed repository checks.\n".repeat(15) +
      "FeatureTaskRuntimeSerialRepairTest failed: expected completed but was blocked at RepairTest.kt:42"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(420.dp)) {
          PeekTimeline(listOf(PeekTimelineRow("13:27", short, null), PeekTimelineRow("13:28", failure, PeekState.Wait)))
        }
      }
    }
    fun layout(): TextLayoutResult {
      val layouts = mutableListOf<TextLayoutResult>()
      onNodeWithText(failure).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      return layouts.single()
    }

    assertEquals(3, layout().lineCount)
    assertTrue(layout().getLineEnd(2, visibleEnd = true) < failure.length)
    onNodeWithText(short).assertExists()
    onNodeWithText("Show details")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
      .performClick()
    assertTrue(layout().lineCount > 3)
    assertEquals(failure, layout().layoutInput.text.text)
    assertTrue(!layout().hasVisualOverflow)
    onNodeWithText("Show less")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
      .performClick()
    assertEquals(3, layout().lineCount)
  }
}
