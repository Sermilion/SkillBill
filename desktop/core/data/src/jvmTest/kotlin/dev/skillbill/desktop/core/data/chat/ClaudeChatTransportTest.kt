package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeChatTransportTest {
  // A wrong flag denies the agent tools the execution has, loses the conversation on resume, or fails every fish user's turn.
  @Test
  fun `arguments follow the agent and the turn with permission checks skipped`() {
    val first = claudeArguments(chatRequest(agent = ChatAgent("claude", "opus", "high"), firstTurn = true))
    assertEquals(listOf("--model", "opus", "--effort", "high"), first.subList(5, 9))
    assertEquals("session-1", first[first.indexOf("--session-id") + 1])
    assertFalse("--resume" in first)
    assertTrue(first.last().contains("same access as the agent"))

    val next = claudeArguments(chatRequest(firstTurn = false, mode = ChatPermissionMode.ReadOnly))
    assertFalse("--model" in next || "--effort" in next || "--session-id" in next)
    assertEquals("session-1", next[next.indexOf("--resume") + 1])
    assertTrue(next.last().contains("Pause and act"))
    for (arguments in listOf(first, next)) {
      assertTrue("--dangerously-skip-permissions" in arguments)
      val restricting = setOf("--restricted", "--tools", "--allowedTools", "--permission-mode", "--permission-prompts")
      assertTrue(arguments.none { it in restricting || it == "operator prompt" })
    }

    assertEquals("/bin/sh", agentCommand("claude", next, "/usr/bin/fish").first())
  }
}
