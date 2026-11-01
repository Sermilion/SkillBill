package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatActivityTest {
  private val agent = ChatAgent("codex", null, null)

  @Test
  fun `tool events show file edits and commands while the completed turn always returns to ready`() {
    val session = ChatSession(workItem("SKILL-1"), "session", Job())
    fun status(busy: Boolean = true) = chatActivityStatus(busy, agent, false, session.transcript.value)

    assertEquals("Thinking...", status())
    session.appendDelta("I'll implement this.")
    assertEquals("Responding...", status())
    val read = ChatToolCall("read", "Read", "View.kt", "Read the view")
    session.appendTool(read)
    assertEquals("Reading files...", status())
    session.recordToolResult(ChatEvent.ToolResult(read.id, "contents", false))
    val edit = ChatToolCall("edit", "functions.apply_patch", "patch", "Update the view")
    session.appendTool(edit)
    assertEquals("Editing files...", status())
    session.recordToolResult(ChatEvent.ToolResult(edit.id, "done", false))
    assertEquals("Working on changes...", status())
    val command = ChatToolCall("check", "shell", "./gradlew check", "Check the change")
    session.appendTool(command)
    assertEquals("Running command...", status())
    session.recordToolResult(ChatEvent.ToolResult(command.id, "passed", false))
    assertEquals("Working on changes...", status())
    session.endStreaming()
    assertEquals("Ready", status(busy = false))
  }

  @Test
  fun `failed edits and promises in a finished reply never imply implementation is still running`() {
    val call = ChatToolCall("patch", "mcp__tools__apply_patch", "patch", null)
    val failed = ChatToolEntry(call, ChatEvent.ToolResult(call.id, "rejected", true))
    val reply = ChatMessage.Agent("I haven't added the button.", streaming = true, tools = listOf(failed))
    assertEquals("Responding...", chatActivityStatus(true, agent, false, listOf(reply)))
    assertEquals("Ready", chatActivityStatus(false, agent, false, listOf(reply)))
  }
}
