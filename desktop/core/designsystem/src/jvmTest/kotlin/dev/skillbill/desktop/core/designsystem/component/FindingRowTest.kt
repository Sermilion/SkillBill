package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FindingRowTest {
  @Test
  fun `toggle tracks finding identity when metadata stays present`() = runComposeUiTest {
    var title by mutableStateOf("First finding title")
    var location by mutableStateOf("src/a/First.kt:10")
    var meta by mutableStateOf("persistence · high confidence")
    var ref by mutableStateOf("F-001")
    setContent {
      SkillBillTheme(reducedMotion = true) {
        FindingRow(title = title, severity = "major", ref = ref, location = location, meta = meta)
      }
    }

    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
    onNodeWithText("First.kt").assertExists()
    onNodeWithText("persistence · high confidence").assertExists()
    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).assertExists()

    runOnIdle {
      title = "Second finding title"
      location = "src/b/Second.kt:20"
      meta = "testing · medium confidence"
      ref = "F-002"
    }
    onNodeWithText("First.kt").assertDoesNotExist()
    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
    onNodeWithText("Second.kt").assertExists()
    onNodeWithText("testing · medium confidence").assertExists()
    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).assertExists()
  }
}
