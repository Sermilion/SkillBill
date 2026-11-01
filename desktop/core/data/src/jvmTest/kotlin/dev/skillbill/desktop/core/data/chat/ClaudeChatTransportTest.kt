package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeChatTransportTest {
  // A wrong flag denies the agent tools the execution has, loses the conversation on resume, ends the process after one turn,
  // or fails every fish user's turn.
  @Test
  fun `arguments keep one streaming process per session with permission checks skipped`() {
    val first = claudeArguments(chatRequest(agent = ChatAgent("claude", "opus", "high"), firstTurn = true))
    assertEquals("stream-json", first[first.indexOf("--input-format") + 1])
    assertTrue("--replay-user-messages" in first)
    assertEquals("opus", first[first.indexOf("--model") + 1])
    assertEquals("high", first[first.indexOf("--effort") + 1])
    assertEquals("session-1", first[first.indexOf("--session-id") + 1])
    assertFalse("--resume" in first)
    assertTrue(first.last().contains("same access as the agent"))

    val readOnly = chatRequest(firstTurn = false, mode = ChatPermissionMode.ReadOnly)
    val next = claudeArguments(readOnly)
    assertFalse("--model" in next || "--effort" in next || "--session-id" in next)
    assertEquals("session-1", next[next.indexOf("--resume") + 1])
    assertFalse(next.last().contains("Pause and act"))
    assertTrue(claudeMessage(readOnly).contains("Pause and act") && claudeMessage(readOnly).endsWith("operator prompt"))
    assertEquals("operator prompt", claudeMessage(chatRequest()))
    for (arguments in listOf(first, next)) {
      assertTrue("--dangerously-skip-permissions" in arguments)
      val restricting = setOf("--restricted", "--tools", "--allowedTools", "--permission-mode", "--permission-prompts")
      assertTrue(arguments.none { it in restricting || it == "operator prompt" })
    }

    assertEquals("/bin/sh", agentCommand("claude", next, "/usr/bin/fish").first())
  }
}
