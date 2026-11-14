package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TourSpotlightTest {
  @Test
  fun `the steer step's pipeline spotlight draws through its grow-in frames`() = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent {
      SkillBillTheme(reducedMotion = false) {
        TourControlScene(step = 2, replayToken = 0, beat = 0, timeline = tourSteerTimeline())
      }
    }

    repeat(90) { mainClock.advanceTimeByFrame() }

    onNodeWithText("The same steps every time").assertExists()
  }
}
