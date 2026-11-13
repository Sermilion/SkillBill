package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ExpandableResultRowTest {
  @Test
  fun `toggle exposes expanded state and keeps extra controls outside the row target`() = runComposeUiTest {
    var open by mutableStateOf(false)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ExpandableResultRow(
          open = open,
          onToggle = { open = !open },
          toggleLabel = "Toggle row",
          leadingOutside = { BasicText("check") },
          endOutside = { BasicText("route") },
          ref = { BasicText("AC-1") },
          title = { BasicText("Recorded criterion text") },
          stateBadge = { ResultRowBadge("Met", SkillBillTheme.colors.stDone) },
          disclosure = { BasicText("fixed in attempt 2") },
        )
      }
    }

    onNodeWithText("check").assertHasNoClickAction()
    onNodeWithText("route").assertHasNoClickAction()
    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).assertExists()
    onNodeWithText("fixed in attempt 2").assertExists()
  }

  @Test
  fun `sparse row without disclosure is not an expand control`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ExpandableResultRow(
          title = { BasicText("Short recorded title") },
          stateBadge = { ResultRowBadge("Met", SkillBillTheme.colors.stDone) },
        )
      }
    }

    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).assertDoesNotExist()
    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).assertDoesNotExist()
    onNodeWithText("Short recorded title").assertHasNoClickAction()
  }
}
