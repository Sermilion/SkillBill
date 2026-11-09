package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardGrants
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalCardOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatApprovalCardTest {
  @Test
  fun `an always-allow decision names the rule instead of the one-time option it answered with`() {
    fun namesOneTimeOption(decisionSource: String) =
      settled(decisionSource).decisionLines().any { it.contains(ONE_TIME_LABEL) }

    assertEquals(false, namesOneTimeOption("operator_rule"))
    assertEquals(false, namesOneTimeOption("stored_rule"))
    assertEquals(true, namesOneTimeOption("operator_once"))
  }

  @Test
  fun `stored_rule with rulePattern names the pattern in decision lines`() {
    val lines = settled("stored_rule").copy(rulePattern = "git status *").decisionLines()
    assertTrue(lines.any { it.contains("git status *") })
    assertTrue(lines.any { it == "Rule rule-1" })
  }

  @Test
  fun `Save rule sends the edited pattern through onDecide`() = runComposeUiTest {
    val decisions = mutableListOf<Triple<String, Boolean, String?>>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatApprovalCard(
          ChatMessage.Approval(
            pendingCard(grants = ApprovalCardGrants(null, null, null, suggestedPattern = "git status *")),
          ),
          ChatApprovalActions(true, { _, option, always, pattern -> decisions += Triple(option, always, pattern) }, {}),
        )
      }
    }
    onNodeWithText("Always allow similar…").performClick()
    onNodeWithText("git status *").assertIsDisplayed()
    onNodeWithContentDescription("Pattern").performTextReplacement("git status *!")
    onNodeWithText("Save rule").performClick()
    assertEquals(listOf(Triple<String, Boolean, String?>("allow", true, "git status *!")), decisions)
  }

  @Test
  fun `a disabled similar button shows its unavailable reason`() = runComposeUiTest {
    val reason = "Not eligible for a pattern rule: the command uses shell operators, substitution or redirects."
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatApprovalCard(
          ChatMessage.Approval(pendingCard(grants = ApprovalCardGrants(null, null, reason, "git *"))),
          ChatApprovalActions(true, { _, _, _, _ -> }, {}),
        )
      }
    }
    onNodeWithText("Allow once").assertIsEnabled()
    onNodeWithText("Always allow similar…").assertIsNotEnabled()
    onNodeWithText("Always allow similar is unavailable: $reason").assertIsDisplayed()
  }

  @Test
  fun `a refusal after Save keeps the editor open and shows the reason`() = runComposeUiTest {
    val message = mutableStateOf<ChatMessage.Approval>(
      ChatMessage.Approval(pendingCard(grants = ApprovalCardGrants(null, null, null, suggestedPattern = "git *"))),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatApprovalCard(message.value, ChatApprovalActions(true, { _, _, _, _ -> }, {}))
      }
    }
    onNodeWithText("Always allow similar…").performClick()
    onNodeWithContentDescription("Pattern").performTextReplacement("git *!")
    onNodeWithText("Save rule").performClick()
    runOnIdle { message.value = message.value.copy(refusal = "Pattern refused: it does not match this request.") }
    onNodeWithText("Save rule").assertIsDisplayed()
    onNodeWithText("git *!").assertIsDisplayed()
    onNodeWithText("Pattern refused: it does not match this request.").assertIsDisplayed()
  }

  private fun pendingCard(grants: ApprovalCardGrants) = ApprovalCard(
    key = ApprovalCardKey("approval-1", "repo", "run-1", "gen-1", "claude", "request-1", false),
    checkoutRoot = "/repo",
    providerSessionId = null,
    title = "Bash",
    reason = null,
    subject = "git status",
    inputText = "git status",
    options = listOf(
      ApprovalCardOption("allow", "Allow", ALLOW_KIND),
      ApprovalCardOption("deny", "Deny", "deny"),
    ),
    actionFingerprint = null,
    authorityScope = null,
    createdAt = "2026-11-07T00:00:00Z",
    deadlineAt = "2026-11-07T00:10:00Z",
    state = "pending",
    decisionSource = null,
    selectedOptionId = null,
    ruleId = null,
    providerPayload = "{}",
    grants = grants,
    repositoryName = "app",
  )

  private fun settled(decisionSource: String) = ApprovalCard(
    key = ApprovalCardKey("approval-1", "repo", "run-1", "gen-1", "claude", "request-1", false),
    checkoutRoot = "/repo",
    providerSessionId = null,
    title = "Bash",
    reason = null,
    subject = "curl -sI https://example.com",
    inputText = "curl -sI https://example.com",
    options = listOf(
      ApprovalCardOption("allow_once", ONE_TIME_LABEL, "one_time_allow"),
      ApprovalCardOption("deny", "Deny", "deny"),
    ),
    actionFingerprint = null,
    authorityScope = null,
    createdAt = "2026-11-07T00:00:00Z",
    deadlineAt = "2026-11-07T00:10:00Z",
    state = "allowed",
    decisionSource = decisionSource,
    selectedOptionId = "allow_once",
    ruleId = "rule-1".takeIf { decisionSource != "operator_once" },
    providerPayload = "{}",
    repositoryName = "app",
  )

  private companion object {
    const val ONE_TIME_LABEL = "One-time option"
    const val ALLOW_KIND = "allow"
  }
}
