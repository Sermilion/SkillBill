package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TourSpotlightTest {
  @Test
  fun `the step four subtasks spotlight draws through its grow-in frames`() = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent {
      SkillBillTheme(reducedMotion = false) {
        TourRunDetailScene(step = 4, replayToken = 0, onEnd = {})
      }
    }

    repeat(90) { mainClock.advanceTimeByFrame() }

    onNodeWithText("Subtasks").assertExists()
  }
}
