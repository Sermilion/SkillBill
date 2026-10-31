package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatSessionTest {
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
