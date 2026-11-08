package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SbPickerButtonTest {
  @Test
  fun `static and disabled pickers cannot open and only clickable pickers show a chevron`() = runComposeUiTest {
    var clicked = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          SbPickerButton("Repo", onClick = { clicked = true }, chevronDescription = "open repo")
          SbPickerButton("Static", onClick = null, chevronDescription = "open static")
          SbPickerButton("Off", onClick = {}, enabled = false)
        }
      }
    }

    val repo = onNodeWithText("Repo")
    assertEquals(Role.Button, repo.fetchSemanticsNode().config[SemanticsProperties.Role])
    repo.performClick()
    runOnIdle { assertEquals(true, clicked) }
    onNodeWithContentDescription("open repo", useUnmergedTree = true).assertExists()
    onNodeWithText("Static").assert(hasNoClickAction())
    onNodeWithContentDescription("open static", useUnmergedTree = true).assertDoesNotExist()
    onNodeWithText("Off").assertIsNotEnabled()
  }
}
