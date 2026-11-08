package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RowSurfaceTest {
  @Test
  fun `selected surface keeps caller owned click and selection while passive rows stay passive`() = runComposeUiTest {
    var clicked = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          val selectedInteraction = remember { MutableInteractionSource() }
          Box(
            Modifier.size(100.dp, 30.dp)
              .sbRowSurface(selectedInteraction, selected = true)
              .clickable(selectedInteraction, indication = null, role = Role.Button) { clicked = true }
              .semantics { selected = true }
              .testTag("selected-row"),
          )
          val passiveInteraction = remember { MutableInteractionSource() }
          Box(Modifier.size(100.dp, 30.dp).sbRowSurface(passiveInteraction, selected = true).testTag("passive-row"))
          Box(
            Modifier.size(100.dp, 30.dp)
              .background(SkillBillTheme.colors.bgSelected)
              .testTag("selected-fill-reference"),
          )
        }
      }
    }

    val selected = onNodeWithTag("selected-row")
    val pixels = selected.captureToImage().toPixelMap()
    val reference = onNodeWithTag("selected-fill-reference").captureToImage().toPixelMap()
    assertEquals(reference[reference.width / 2, reference.height / 2], pixels[pixels.width / 2, pixels.height / 2])
    selected.performClick()
    runOnIdle { assertTrue(clicked) }
    val selectedSemantics = selected.fetchSemanticsNode().config
    assertEquals(true, selectedSemantics[SemanticsProperties.Selected])
    assertEquals(Role.Button, selectedSemantics[SemanticsProperties.Role])

    val passiveSemantics = onNodeWithTag("passive-row").fetchSemanticsNode().config
    assertTrue(SemanticsProperties.Selected !in passiveSemantics)
    onNodeWithTag("passive-row").assert(hasNoClickAction())
  }
}
