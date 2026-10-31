package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeChatTransportTest {
  // A wrong flag lets a running execution be edited, loses the conversation on resume, or fails every fish user's turn.
  @Test
  fun `arguments follow the agent, the turn and the permission mode`() {
    val first = claudeArguments(chatRequest(agent = ChatAgent("claude", "opus", "high"), firstTurn = true))
    assertEquals(listOf("--model", "opus", "--effort", "high"), first.subList(5, 9))
    assertEquals("session-1", first[first.indexOf("--session-id") + 1])
    assertFalse("--resume" in first)

    val next = claudeArguments(chatRequest(firstTurn = false, mode = ChatPermissionMode.ReadOnly))
    assertFalse("--model" in next || "--effort" in next || "--session-id" in next)
    assertEquals("session-1", next[next.indexOf("--resume") + 1])
    assertEquals("Read,Grep,Glob,Bash", next[next.indexOf("--tools") + 1])
    val rules = next.subList(next.indexOf("--allowedTools") + 1, next.indexOf("--system-prompt-snapshot"))
    assertFalse("Edit" in rules || "Write" in rules)
    val stateChanging = listOf("recover", "pause", "stop", "resume", "repair", "retry-blocked", "abandon")
    assertTrue(rules.none { rule -> rule.startsWith("Bash(skill-bill *") || stateChanging.any { it in rule } })
    val forbidden = setOf("--dangerously-skip-permissions", "bypassPermissions", "--no-session-persistence", "--add-dir")
    assertTrue((first + next).none { it in forbidden || it == "operator prompt" })

    assertEquals("/bin/sh", claudeCommand(next, "/usr/bin/fish").first())
  }
}
