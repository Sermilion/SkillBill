package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.QaChecklistTarget
import dev.skillbill.desktop.core.domain.model.QaTargetState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class QaTargetChipTest {
  @Test
  fun `a target with a detail shows its name, its detail and its status`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        QaTargetChip(QaChecklistTarget("desktop", QaTargetState.Ready, detail = "up"), live = true)
      }
    }

    onNodeWithText("desktop").assertExists()
    onNodeWithText("up").assertExists()
    onNodeWithContentDescription("Ready").assertExists()
  }

  @Test
  fun `a target with a blank detail shows only its name`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        QaTargetChip(QaChecklistTarget("web", QaTargetState.Skipped, detail = " "), live = false)
      }
    }

    onNodeWithText("web").assertExists()
    onNodeWithText(" ").assertDoesNotExist()
  }
}
