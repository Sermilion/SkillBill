package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    assertEquals("Running command...", status())
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
    assertEquals("Running command...", label("functions.exec_command", """{"cmd":"./gradlew check"}"""))
    assertEquals("Calling codegraph MCP", label("mcp__codegraph__codegraph_explore", "{}"))
    assertEquals("Editing View.kt", label("functions.apply_patch", "*** Begin Patch\n*** Update File: /repo/View.kt\n@@\n-old\n+new"))
    assertEquals("Reading files...", label("Read", "{incomplete"))
    assertEquals("Searching...", label("search", "{}"))
    assertEquals("Running command...", label("functions.exec", "const results = await tools.exec_command(...)"))
    val longQuery = label("search", """{"query":"${"x".repeat(200)}"}""")
    assertEquals("Searching for " + "x".repeat(71) + "...", longQuery)
  }

  @Test
  fun `shell commands and wrapper scripts never become pill text even when summaries repeat them`() {
    val commands = listOf(
      "./gradlew check",
      "bash -lc 'cd /repo && ./gradlew check --stacktrace'",
      "git status --short && " + "echo details; ".repeat(50),
      "python3 - <<'PY'\nprint('checking repository')\nPY",
      "const results = await tools.exec_command({cmd: 'git status'});",
    )
    for (name in listOf("Bash", "shell", "functions.exec", "functions.exec_command", "run", "command_execution")) {
      for (command in commands) {
        for (input in listOf(command, JsonObject(mapOf("cmd" to JsonPrimitive(command))).toString())) {
          val call = ChatToolCall("shell", name, input, command)
          val reply = ChatMessage.Agent("", streaming = true, tools = listOf(ChatToolEntry(call)))
          assertEquals("Running command...", chatActivityStatus(true, agent, false, listOf(reply)))
        }
      }
    }
    assertEquals("Using tools...", toolActivityLabel(ChatToolCall("unknown", commands[1], "", commands[1])))
    assertEquals("Searching...", toolActivityLabel(ChatToolCall("search", "search", "{}", commands[1])))
    assertEquals("Searching...", toolActivityLabel(ChatToolCall("search", "search", """{"query":"ls; cat file"}""", null)))
    assertEquals("Reading files...", toolActivityLabel(ChatToolCall("read", "Read", "{broken\ncommand", commands[1])))
  }

  @Test
  fun `service calls and explicit waits describe activity without exposing their payloads`() {
    val mcp = ChatToolCall("mcp", "mcp__skill-bill__work_status", """{"command":"long internal command"}""", "work_status")
    val wait = ChatToolCall("wait", "functions.wait", """{"cell_id":"private-id"}""", null)
    val reply = ChatMessage.Agent("", streaming = true, tools = listOf(ChatToolEntry(mcp)))
    assertEquals("Calling skill-bill MCP", chatActivityStatus(true, agent, false, listOf(reply)))
    val waiting = reply.copy(tools = listOf(ChatToolEntry(wait)))
    assertEquals("Waiting for response...", chatActivityStatus(true, agent, false, listOf(waiting)))
    assertEquals("Ready", chatActivityStatus(false, agent, false, listOf(waiting)))
    assertEquals("Reading MainActivity.kt", toolActivityLabel(ChatToolCall("read", "Read", """{"file_path":"/repo/MainActivity.kt"}""", null)))
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
