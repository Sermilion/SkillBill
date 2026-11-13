package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Instant

class ChatSessionTest {
  @Test
  fun `only agents with resumable provider sessions continue without a move`() {
    val session = ChatSession(workItem("SKILL-1"), "session-1", Job())
    fun entry(agentId: String) = ChatHistoryEntry(
      "saved", null, ChatAgent(agentId, null, null), Instant.fromEpochMilliseconds(1), "saved chat", emptyList(),
    )

    assertTrue(session.canContinue(entry("claude"), "/repo"))
    assertTrue(session.canContinue(entry("codex"), "/repo"))
    assertFalse(session.canContinue(entry("cursor"), "/repo"))
    assertFalse(session.canContinue(entry("unavailable"), "/repo"))
    assertFalse(session.canContinue(entry("retired-agent"), "/repo"))
  }

  @Test
  fun `repeated tool announcements keep one row and out of order results reach their own commands`() {
    val session = ChatSession(workItem("SKILL-1"), "session-1", Job())
    val status = ChatToolCall("status", "Bash", "git status", "Check status")
    val diff = ChatToolCall("diff", "Bash", "git diff", "Read diff")
    session.appendTool(status)
    session.appendTool(diff)
    session.recordToolResult(ChatEvent.ToolResult(diff.id, "diff output", false))
    session.recordToolResult(ChatEvent.ToolResult(status.id, "fatal: no repository", true))
    session.appendTool(status)
    session.appendDelta("The worktree is missing.")
    session.endStreaming()

    val message = assertIs<ChatMessage.Agent>(session.transcript.value.single())
    assertEquals(listOf(status, diff), message.tools.map { it.call })
    assertEquals(listOf("fatal: no repository", "diff output"), message.tools.map { it.result?.output })
    assertEquals(listOf(true, false), message.tools.map { it.result?.isError })
    assertEquals("The worktree is missing.", message.text)
  }
}
