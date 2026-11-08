package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SbTagTest {
  @Test
  fun `clickable tags expose a button role and fire their callback`() = runComposeUiTest {
    var clicked = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          SbTag("x", onClick = { clicked = true })
          SbTag("y")
        }
      }
    }
    val clickable = onNodeWithText("x")
    assertEquals(Role.Button, clickable.fetchSemanticsNode().config[SemanticsProperties.Role])
    clickable.performClick()
    runOnIdle { assertEquals(true, clicked) }
    onNodeWithText("y").assert(hasNoClickAction())
  }

  @Test
  fun `tag variants share the 18dp minimum height`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          SbTag("default", Modifier.testTag("default"))
          SbTag("tone", Modifier.testTag("tone"), tone = Color.Red)
          SbTag("outlined", Modifier.testTag("outlined"), outlined = true)
        }
      }
    }
    onNodeWithTag("default").assertHeightIsEqualTo(18.dp)
    onNodeWithTag("tone").assertHeightIsEqualTo(18.dp)
    onNodeWithTag("outlined").assertHeightIsEqualTo(18.dp)
  }
}
