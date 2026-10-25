package dev.skillbill.tray.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.ui.Feedback
import dev.skillbill.tray.core.ui.FeedbackHost
import dev.skillbill.tray.core.ui.FeedbackMessages
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.acknowledged
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeedbackPopupTest {
  @Test
  fun `an action error appears as a popup then expires and acknowledges its feedback`() = runComposeUiTest {
    mainClock.autoAdvance = false
    var pending by mutableStateOf(listOf(Feedback(1, FeedbackTone.PROBLEM, "Launch refused")))
    setContent {
      DesignSystemTheme {
        FeedbackHost(Modifier.fillMaxSize()) {
          FeedbackMessages(pending) { pending = pending.acknowledged(it) }
        }
      }
    }
    mainClock.advanceTimeBy(500)
    onNodeWithText("Launch refused").assertExists()
    mainClock.advanceTimeBy(5_000)
    onNodeWithText("Launch refused").assertDoesNotExist()
    runOnIdle { assertTrue(pending.isEmpty()) }
  }
}
