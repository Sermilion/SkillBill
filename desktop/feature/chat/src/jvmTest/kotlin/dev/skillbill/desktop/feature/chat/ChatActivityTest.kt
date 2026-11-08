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
    assertEquals("Working...", status())
    val read = ChatToolCall("read", "Read", "View.kt", "Read the view")
    session.appendTool(read)
    assertEquals("Reading View.kt", status())
    session.recordToolResult(ChatEvent.ToolResult(read.id, "contents", false))
    val edit = ChatToolCall("edit", "functions.apply_patch", "patch", "Update the view")
    session.appendTool(edit)
    assertEquals("Editing files...", status())
    session.recordToolResult(ChatEvent.ToolResult(edit.id, "done", false))
    assertEquals("Working...", status())
    val command = ChatToolCall("check", "shell", "./gradlew check", "Check the change")
    session.appendTool(command)
    assertEquals("Running ./gradlew check", status())
    session.recordToolResult(ChatEvent.ToolResult(command.id, "passed", false))
    assertEquals("Working...", status())
    session.endStreaming()
    assertEquals("Ready", status(busy = false))
  }

  @Test
  fun `failed edits and promises in a finished reply never imply implementation is still running`() {
    val call = ChatToolCall("patch", "mcp__tools__apply_patch", "patch", null)
    val failed = ChatToolEntry(call, ChatEvent.ToolResult(call.id, "rejected", true))
    val reply = ChatMessage.Agent("I haven't added the button.", streaming = true, tools = listOf(failed))
    assertEquals("Working...", chatActivityStatus(true, agent, false, listOf(reply)))
    assertEquals("Ready", chatActivityStatus(false, agent, false, listOf(reply)))
  }

  @Test
  fun `activity uses structured tool arguments and names unknown MCP tools`() {
    fun label(name: String, input: String) = toolActivityLabel(ChatToolCall("tool", name, input, null))

    assertEquals("Reading ChatActivity.kt", label("Read", """{"file_path":"/repo/ChatActivity.kt"}"""))
    assertEquals("Editing View.kt", label("Edit", """{"file_path":"C:\\repo\\View.kt"}"""))
    assertEquals("Searching for chatActivityStatus", label("Grep", """{"pattern":"chatActivityStatus"}"""))
    assertEquals("Running ./gradlew check", label("functions.exec_command", """{"cmd":"./gradlew check"}"""))
    assertEquals("Calling codegraph_explore", label("mcp__codegraph__codegraph_explore", "{}"))
    assertEquals("Editing View.kt", label("functions.apply_patch", "*** Begin Patch\n*** Update File: /repo/View.kt\n@@\n-old\n+new"))
    assertEquals("Reading files...", label("Read", "{incomplete"))
    assertEquals("Searching...", label("search", "{}"))
    assertEquals("Calling exec", label("functions.exec", "const results = await tools.exec_command(...)"))
    val longQuery = label("search", """{"query":"${"x".repeat(200)}"}""")
    assertEquals("Searching for " + "x".repeat(71) + "...", longQuery)
  }

  @Test
  fun `finished text does not hide a concurrent tool and completed tools never stay active`() {
    val first = ChatToolCall("first", "Read", "First.kt", null)
    val second = ChatToolCall("second", "Read", "Second.kt", null)
    val reply = ChatMessage.Agent("Earlier commentary", streaming = true, tools = listOf(
      ChatToolEntry(first),
      ChatToolEntry(second, ChatEvent.ToolResult(second.id, "done", false)),
    ))
    assertEquals("Reading First.kt", chatActivityStatus(true, agent, false, listOf(reply)))
    val settled = reply.copy(tools = reply.tools.map { it.copy(result = ChatEvent.ToolResult(it.call.id, "done", false)) })
    assertEquals("Working...", chatActivityStatus(true, agent, false, listOf(settled)))
    assertEquals("Execution unavailable", chatActivityStatus(true, agent, true, listOf(reply)))
  }
}
