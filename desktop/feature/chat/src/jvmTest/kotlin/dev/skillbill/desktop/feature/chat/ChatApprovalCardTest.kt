package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalCardOption
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatApprovalCardTest {
  @Test
  fun `an always-allow decision names the rule instead of the one-time option it answered with`() {
    fun namesOneTimeOption(decisionSource: String) =
      settled(decisionSource).decisionLines().any { it.contains(ONE_TIME_LABEL) }

    assertEquals(false, namesOneTimeOption("operator_rule"))
    assertEquals(false, namesOneTimeOption("stored_rule"))
    assertEquals(true, namesOneTimeOption("operator_once"))
  }

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
  }
}
