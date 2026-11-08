package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class UsagePresentationTest {
  @Test
  fun `a full width action footer cannot hide the missing data reason`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        UsagePanel(
          UsageUiState(provider = "Cursor", account = "Current local account", status = "Usage unavailable", reason = "The account plan is unknown"),
          Modifier.width(316.dp),
          footerContent = { BasicText("Manual refresh is unavailable for this source", Modifier.fillMaxWidth()) },
        )
      }
    }

    onNodeWithText("The account plan is unknown").assertIsDisplayed()
    onNodeWithText("Manual refresh is unavailable for this source").assertIsDisplayed()
  }

  @Test
  fun `details keep later meters and status rows do not invent progress`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        UsagePanel(
          UsageUiState(
            provider = "Codex",
            account = "Current local account",
            accountExplanation = "This is the selected local account, not a verified account for a particular run.",
            status = "Could not update usage",
            reason = "The previous observation remains in effect",
            requestPermission = "Rejected",
            stale = true,
            expired = true,
            detailMeters = listOf(
              UsageUiMeter("window", "Window", "Rolling 2h", "40% used", progress = .4f, progressLabel = "Used 40 percent", progressStateDescription = "Stale"),
              UsageUiMeter("status", "Plan", "Period not reported", "Status unknown"),
              UsageUiMeter("third", "Weekly pool", "Billing period", "Unlimited"),
              UsageUiMeter("balance", "Credits", "Balance", "Balance 12.50 credits"),
              UsageUiMeter("money", "Spend", "Billing period", "12.50 USD"),
              UsageUiMeter("expired", "Expired pool", "Rolling 6h", "150 credits / 100 credits used", progress = 1f, progressLabel = "Used 150 credits of 100 credits", progressStateDescription = "Expired, Limit reached", stateText = "Expired allowance", exhausted = true),
            ),
          ),
        )
      }
    }

    onNodeWithText("Weekly pool").assertIsDisplayed()
    onNodeWithText("Unlimited").assertIsDisplayed()
    onNodeWithText("Balance 12.50 credits").assertIsDisplayed()
    onNodeWithText("12.50 USD").assertIsDisplayed()
    onNodeWithText("150 credits / 100 credits used").assertIsDisplayed()
    onNodeWithText("Stale data").assertIsDisplayed()
    onNodeWithText("Expired allowance").assertIsDisplayed()
    onNodeWithText("Request permission: Rejected").assertIsDisplayed()
    onNodeWithText("The previous observation remains in effect").assertIsDisplayed()
    onNodeWithText("This is the selected local account, not a verified account for a particular run.").assertIsDisplayed()
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(2)
  }
}
