package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GroupLabelTest {
  @Test
  fun `label exposes heading semantics and renders its count and end content at 24dp`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        GroupLabel("Files", count = "3", end = { BasicText("end") })
      }
    }

    val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    onNode(heading)
      .assertExists()
      .assertHeightIsEqualTo(24.dp)
    listOf("Files", "3", "end").forEach { text ->
      onNode(hasText(text) and hasAnyAncestor(heading)).assertIsDisplayed()
    }
  }

  @Test
  fun `long labels preserve the count and trailing content in a narrow pane`() = runComposeUiTest {
    val label = "A long module name ".repeat(20)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(240.dp)) {
          GroupLabel(label, count = "3", end = { BasicText("+12"); BasicText("-4") })
        }
      }
    }

    val heading = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode().boundsInRoot
    val texts = listOf(label, "3", "+12", "-4").map { text ->
      onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    }
    texts.forEach { bounds ->
      assertTrue(bounds.width > 0)
      assertTrue(bounds.left >= heading.left && bounds.right <= heading.right)
    }
    texts.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
  }
}
