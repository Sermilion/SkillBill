package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class UsagePresentationTest {
  private val meters = listOf(
    UsageUiMeter("five", "5 hour", "7%", progress = .07f, resetIn = "1h 30m", resetAt = "11:30"),
    UsageUiMeter("weekly", "Weekly", "43%", progress = .43f, resetIn = "2d 3h", resetAt = "Tue 13:00"),
    UsageUiMeter("monthly", "Monthly", "90%", progress = .9f, resetIn = "20d 1h"),
  )

  @Test
  fun `the card is only as tall as what it shows`() = runComposeUiTest {
    var state by mutableStateOf(UsageUiState("Codex", "codex", meters = meters))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        UsageCard(state, onOpen = {}, modifier = Modifier.width(240.dp).testTag("card"))
      }
    }

    onNodeWithText("5 hour · 1h 30m").assertIsDisplayed()
    onNodeWithText("Monthly · 20d 1h").assertDoesNotExist()
    onNodeWithTag("card").assertHeightIsEqualTo(usageCardHeight(2))

    state = UsageUiState("Codex", "codex", meters = meters.take(1))
    onNodeWithTag("card").assertHeightIsEqualTo(usageCardHeight(1))

    state = UsageUiState("Claude", "claude", message = "Not signed in")
    onNodeWithText("Not signed in").assertIsDisplayed()
    onNodeWithTag("card").assertHeightIsEqualTo(usageCardHeight(1))

    state = UsageUiState("Cursor", "cursor", nameOnly = true)
    onNodeWithText("Cursor").assertIsDisplayed()
    onNodeWithTag("card").assertHeightIsEqualTo(usageCardHeight(0))
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

  @Test
  fun `a name-only agent shows only the provider controls and settings`() = runComposeUiTest {
    var state by mutableStateOf(UsageUiState("Cursor", "cursor", nameOnly = true))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        UsagePanel(
          state,
          Modifier.width(316.dp).testTag("panel"),
          providerControls = { Box(Modifier.fillMaxWidth().height(32.dp).testTag("tabs")) },
          settingsContent = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("settings")) },
          footerContent = { Box(Modifier.width(60.dp).height(24.dp).testTag("footer")) },
        )
      }
    }
    fun gap() = onNodeWithTag("settings").getUnclippedBoundsInRoot().top - onNodeWithTag("tabs").getUnclippedBoundsInRoot().bottom
    fun below() = onNodeWithTag("panel").getUnclippedBoundsInRoot().bottom - onNodeWithTag("settings").getUnclippedBoundsInRoot().bottom

    assertEquals(12.dp, gap())
    assertEquals(0.dp, below())
    onNodeWithTag("footer").assertDoesNotExist()

    state = UsageUiState("Claude", "claude", message = "Not signed in")
    waitForIdle()
    // The message area is its 16dp padding around the line, with no blank space below it.
    assertEquals(onNodeWithText("Not signed in").getUnclippedBoundsInRoot().let { it.bottom - it.top } + 32.dp, gap())
    onNodeWithTag("footer").assertIsDisplayed()
    // Divider plus the footer row's 8dp padding on both sides of the 24dp content.
    assertEquals(41.dp, below())
  }
}
