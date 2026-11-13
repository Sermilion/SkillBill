package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.PhaseCardOutput
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PlanningRecordedOutputTest {
  @Test
  fun `saved preplan sections expand to their evidence in a narrow card`() = runComposeUiTest {
    val output = PhaseCardOutput(
      null, null, null, null, null, null, null, null, null,
      summary = "Read the project context",
      text = """
        ## History and decisions
        Keep previous migration steps for supported records.
        ## Reusable patterns
        Use the existing transaction owner.
        ## Add-ons
        bill-kotlin-code-review-persistence
        ## Validation strategy
        Run the populated-store migration tests.
      """.trimIndent(),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(360.dp)) { PreplanRecordedOutput(emptyDetail, output) {} }
      }
    }

    onNodeWithText("Digest").assertExists()
    onNode(hasText("History and decisions") and hasClickAction()).performClick()
    onNode(hasText("Keep previous migration steps for supported records.") and !hasClickAction()).assertExists()
    onNode(hasText("Validation strategy") and hasClickAction()).performClick()
    onNode(hasText("Run the populated-store migration tests.") and !hasClickAction()).assertExists()
    onNodeWithText("Shared by every subtask in this goal.").assertExists()
  }

  @Test
  fun `missing planning evidence leaves an explicit result instead of an empty card`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) { PlanningRecordedOutput(null, emptyList()) }
    }

    onNodeWithText("The planning output was not retained for this run.").assertExists()
  }
}
