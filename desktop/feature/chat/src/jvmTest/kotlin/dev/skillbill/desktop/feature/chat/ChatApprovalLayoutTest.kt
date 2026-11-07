package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardGrants
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalCardOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatApprovalLayoutTest {
  @Test
  fun `large payload stays folded while decision buttons send the exact choice`() = runComposeUiTest {
    var lineHeight = 0.dp
    val decisions = mutableListOf<Pair<String, Boolean>>()
    val actions = ChatApprovalActions(true, { _, option, always -> decisions += option to always }, {})
    setContent {
      SkillBillTheme(reducedMotion = true) {
        lineHeight = SkillBillTheme.type.tSm.lineHeight.value.dp
        LazyColumn(Modifier.width(380.dp)) {
          item { ChatApprovalCard(ChatMessage.Approval(card()), actions) }
        }
      }
    }
    onNodeWithText("payload start", substring = true).assertDoesNotExist()
    onNodeWithText("Allow once").assertIsDisplayed().assertIsEnabled().performClick()
    onNodeWithText("Always allow").assertIsDisplayed().performClick()
    onNodeWithText("Deny").assertIsDisplayed().performClick()
    assertEquals(listOf("allow" to false, "allow" to true, "deny" to false), decisions)
    onNodeWithText("Show details").performClick()
    val details = onNodeWithText("payload start", substring = true)
    details.assertHeightIsEqualTo(lineHeight * 3)
    val range = details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    assertTrue(range.maxValue() > 0f)
    details.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, range.maxValue()) }
    waitForIdle()
    assertEquals(range.maxValue(), range.value())
    onNodeWithText("Allow once").assertIsDisplayed()
    onNodeWithText("Hide details").performClick()
    onNodeWithText("payload start", substring = true).assertDoesNotExist()
  }

  @Test
  fun `settled cards expose scrollable history without decision buttons and restored pending cards stay disabled`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        LazyColumn(Modifier.width(380.dp)) {
          item { ChatApprovalCard(ChatMessage.Approval(card().copy(state = "unsupported")), null) }
          item { ChatApprovalCard(ChatMessage.Approval(card().copy(key = card().key.copy(approvalId = "restored")), restoredCopy = true), ChatApprovalActions(true, { _, _, _ -> }, {})) }
        }
      }
    }
    onNodeWithText("Not supported, so it was refused").assertIsDisplayed()
    onNodeWithText("Allow once").performScrollTo().assertIsNotEnabled()
    onNodeWithText("Always allow").assertIsNotEnabled()
    onNodeWithText("Deny").assertIsNotEnabled()
  }

  private fun card() = ApprovalCard(
    key = ApprovalCardKey("approval", "repo", "run", "generation", "codex", "request", false),
    checkoutRoot = "/repo", providerSessionId = null, title = "Permission to run a command", reason = "Needs access to the service",
    subject = "curl https://example.com", inputText = "payload start\n" + "long provider input\n".repeat(100) + "payload end",
    options = listOf(ApprovalCardOption("allow", "Run this command once", "allow"), ApprovalCardOption("deny", "Refuse this command", "deny")),
    actionFingerprint = null, authorityScope = null, createdAt = "2026-11-07T00:00:00Z", deadlineAt = "2026-11-07T00:10:00Z",
    state = "pending", decisionSource = null, selectedOptionId = null, ruleId = null, providerPayload = "{}",
    grants = ApprovalCardGrants(null, null), repositoryName = "app",
  )
}
