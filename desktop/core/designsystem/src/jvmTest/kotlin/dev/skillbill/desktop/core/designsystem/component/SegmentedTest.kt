package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SegmentedTest {
  @Test
  fun `count renders and disabled options do not select`() = runComposeUiTest {
    var picked = emptyList<String>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Segmented(
          listOf(SegmentedOption("a", "First"), SegmentedOption("b", "Second", count = 3, enabled = false)),
          selected = "a",
          onSelect = { picked = picked + it },
        )
      }
    }

    onNodeWithContentDescription("First")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
      .assertIsSelected()
    onNodeWithText("3").assertExists()
    onNodeWithContentDescription("Second").performClick()
    runOnIdle { assertEquals(emptyList(), picked) }
  }
}
