package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class UsagePresentationTest {
  private val meters = listOf(
    UsageUiMeter("five", "5 hour", "7%", progress = .07f, resetIn = "1h 30m", resetAt = "11:30"),
    UsageUiMeter("weekly", "Weekly", "43%", progress = .43f, resetIn = "2d 3h", resetAt = "Tue 13:00"),
    UsageUiMeter("monthly", "Monthly", "90%", progress = .9f, resetIn = "20d 1h"),
  )

  @Test
  fun `the card keeps one height whatever it shows`() = runComposeUiTest {
    var state by mutableStateOf(UsageUiState("Codex", "codex", meters = meters))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        UsageCard(state, onOpen = {}, modifier = Modifier.width(240.dp).testTag("card"))
      }
    }

    onNodeWithText("5 hour · 1h 30m").assertIsDisplayed()
    onNodeWithText("Monthly · 20d 1h").assertDoesNotExist()
    onNodeWithTag("card").assertHeightIsEqualTo(UsageCardHeight)
    state = UsageUiState("Claude", "claude", message = "Not signed in")
    onNodeWithText("Not signed in").assertIsDisplayed()
    onNodeWithTag("card").assertHeightIsEqualTo(UsageCardHeight)
  }

  @Test
  fun `details show every window with when it resets`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        UsagePanel(UsageUiState("Codex", "codex", meters = meters), Modifier.width(316.dp))
      }
    }

    onNodeWithText("Monthly").assertIsDisplayed()
    onNodeWithText("Resets in 1h 30m · 11:30").assertIsDisplayed()
    onNodeWithText("Resets in 2d 3h · Tue 13:00").assertIsDisplayed()
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(3)
  }
}
