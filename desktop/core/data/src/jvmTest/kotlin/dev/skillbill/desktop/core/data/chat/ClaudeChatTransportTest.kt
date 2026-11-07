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
  fun `arguments keep one streaming process per session with permission requests sent to the host`() {
    val first = claudeArguments(chatRequest(agent = ChatAgent("claude", "opus", "high"), firstTurn = true))
    assertEquals("stream-json", first[first.indexOf("--input-format") + 1])
    assertTrue("--replay-user-messages" in first)
    assertEquals("opus", first[first.indexOf("--model") + 1])
    assertEquals("high", first[first.indexOf("--effort") + 1])
    assertEquals("session-1", first[first.indexOf("--session-id") + 1])
    assertFalse("--resume" in first)
    assertTrue(first.last().contains("only after the operator answers its approval card"))

    val readOnly = chatRequest(firstTurn = false, mode = ChatPermissionMode.ReadOnly)
    val next = claudeArguments(readOnly)
    assertFalse("--model" in next || "--effort" in next || "--session-id" in next)
    assertEquals("session-1", next[next.indexOf("--resume") + 1])
    assertFalse(next.last().contains("Pause and act"))
    assertTrue(claudeMessage(readOnly).contains("Pause and act") && claudeMessage(readOnly).endsWith("operator prompt"))
    assertEquals("operator prompt", claudeMessage(chatRequest()))
    assertEquals("manual", first[first.indexOf("--permission-mode") + 1])
    assertEquals("plan", next[next.indexOf("--permission-mode") + 1])
    for (arguments in listOf(first, next)) {
      assertEquals("host", arguments[arguments.indexOf("--permission-prompts") + 1])
      assertEquals("stdio", arguments[arguments.indexOf("--permission-prompt-tool") + 1])
      val bypassing = setOf("--dangerously-skip-permissions", "--restricted", "--tools", "--allowedTools")
      assertTrue(arguments.none { it in bypassing || it == "operator prompt" })
    }

    assertEquals("/bin/sh", agentCommand("claude", next, "/usr/bin/fish").first())
  }
}
