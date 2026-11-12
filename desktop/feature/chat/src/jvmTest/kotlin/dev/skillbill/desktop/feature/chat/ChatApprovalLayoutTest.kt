package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.skillbill.desktop.core.domain.model.ApprovalRule
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatApprovalLayoutTest {
  @Test
  fun `large payload stays folded while decision buttons send the exact choice`() = runComposeUiTest {
    var lineHeight = 0.dp
    val decisions = mutableListOf<Pair<String, Boolean>>()
    val actions = ChatApprovalActions(true, { _, option, always, _ -> decisions += option to always }, {})
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
          item { ChatApprovalCard(ChatMessage.Approval(card().copy(key = card().key.copy(approvalId = "restored")), restoredCopy = true), ChatApprovalActions(true, { _, _, _, _ -> }, {})) }
        }
      }
    }
    onNodeWithText("Not supported, so it was refused").assertIsDisplayed()
    onNodeWithText("Allow once").performScrollTo().assertIsNotEnabled()
    onNodeWithText("Always allow").assertIsNotEnabled()
    onNodeWithText("Deny").assertIsNotEnabled()
  }

  @Test
  fun `restored approvals keep a green shield with yellow once and green always outcomes`() = runComposeUiTest {
    val source = mutableStateOf("operator_once")
    var green = Color.Unspecified
    var yellow = Color.Unspecified
    setContent {
      SkillBillTheme(reducedMotion = true) {
        green = SkillBillTheme.colors.stDone
        yellow = SkillBillTheme.colors.accentText
        ChatApprovalCard(ChatMessage.Approval(card().copy(state = "allowed", decisionSource = source.value), restoredCopy = true), null)
      }
    }
    for (decision in listOf("operator_once", "operator_rule", "stored_rule")) {
      runOnIdle { source.value = decision }
      val once = decision == "operator_once"
      val label = if (once) "Allowed once" else "Always allowed"
      val layouts = mutableListOf<TextLayoutResult>()
      onNodeWithText(label).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertEquals(if (once) yellow else green, layouts.single().layoutInput.style.color)
      val image = onNodeWithContentDescription("Permission status").captureToImage()
      val pixels = image.toPixelMap()
      assertTrue((0 until image.width).any { x -> (0 until image.height).any { y -> pixels[x, y].toArgb() == green.toArgb() } })
    }
  }

  @Test
  fun `an unavailable repository rule explains why while one time approval and dismissal remain usable`() = runComposeUiTest {
    val reason = "Sandbox and network escalations cannot be saved as repository rules."
    var once = 0
    var dismissed = 0
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatApprovalCard(
          ChatMessage.Approval(card().copy(grants = ApprovalCardGrants(null, reason))),
          ChatApprovalActions(true, { _, _, _, _ -> once++ }, { dismissed++ }),
        )
      }
    }
    onNodeWithText("Always allow").assertIsNotEnabled()
    onNodeWithText("Always allow is unavailable: $reason").assertIsDisplayed()
    onNodeWithText("Allow once").assertIsEnabled().performClick()
    onNodeWithText("Dismiss").assertIsEnabled().performClick()
    assertEquals(1, once)
    assertEquals(1, dismissed)
  }

  @Test
  fun `the similar editor fits a narrow card without clipping the existing buttons`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        LazyColumn(Modifier.width(280.dp)) {
          item {
            ChatApprovalCard(
              ChatMessage.Approval(
                card().copy(grants = ApprovalCardGrants(null, null, null, suggestedPattern = "git status *")),
              ),
              ChatApprovalActions(true, { _, _, _, _ -> }, {}),
            )
          }
        }
      }
    }
    onNodeWithText("Allow once").assertIsDisplayed()
    onNodeWithText("Always allow similar…").performClick()
    onNodeWithText("Save rule").assertIsDisplayed()
    onNodeWithText("Allow once").assertIsDisplayed()
    onNodeWithText("Deny").assertIsDisplayed()
  }

  @Test
  fun `a pattern rule row shows Pattern and Revoke calls back with its id`() = runComposeUiTest {
    var revoked: String? = null
    val tab = RepositoryApprovals("/repo", SupervisorJob(), ChatThread(Clock.System))
    tab.rules.value = RepositoryRulesState.Listed(
      listOf(ApprovalRule("rule-1", "claude", "profile", "rev", "bash", "/repo", "fp", "2026-01-01", pattern = "git *")),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        RepositoryRulesPanel(tab, onRefresh = {}, onRevoke = { revoked = it })
      }
    }
    onNodeWithText("Pattern git *").assertIsDisplayed()
    onNodeWithContentDescription("Revoke rule rule-1").performClick()
    assertEquals("rule-1", revoked)
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
