package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class AgentChatTransportsTest {
  private val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")
  private val store = ChatStore(host, RecordingDiagnostics(), Clock.System)

  /** Answers each turn with the next script of stdout lines and records its command. */
  private class ScriptedLauncher(private val scripts: List<List<ChatProcessOutput>>) : ChatProcessLauncher {
    val commands = mutableListOf<ChatCommand>()

    override fun run(command: ChatCommand) = scripts[commands.size].also { commands += command }.asFlow()
  }

  private fun lines(vararg text: String, code: Int = 0) = text.map { ChatProcessOutput.Line(it) } + ChatProcessOutput.Exited(code, "")

  // Codex names its own thread; losing it, even across an app restart, would start every later turn without the conversation.
  @Test
  fun `codex runs unsandboxed, maps items to events and resumes the reported thread after a restart`() = runTest {
    val launcher = ScriptedLauncher(
      listOf(
        lines(
          """{"type":"thread.started","thread_id":"t-1"}""",
          """{"type":"item.started","item":{"id":"i1","type":"command_execution","command":"git status","status":"in_progress"}}""",
          """{"type":"item.completed","item":{"id":"i1","type":"command_execution","command":"git status","aggregated_output":"clean","exit_code":0,"status":"completed"}}""",
          """{"type":"item.completed","item":{"id":"i2","type":"agent_message","text":"All clean."}}""",
          """{"type":"turn.completed","usage":{}}""",
        ),
        lines("""{"type":"error","message":"stream disconnected"}""", code = 1),
      ),
    )
    val first = CodexChatTransport(host, launcher, store, RecordingDiagnostics()).run(chatRequest(agent = ChatAgent("codex", "gpt-5", "high"))).toList()

    val call = ChatToolCall("i1", "shell", "git status", "git status")
    assertEquals(
      listOf(ChatEvent.ToolUse(call), ChatEvent.ToolResult("i1", "clean", false), ChatEvent.TextDelta("All clean."), ChatEvent.Completed),
      first,
    )
    val firstArgs = launcher.commands[0].argv
    assertTrue("--dangerously-bypass-approvals-and-sandbox" in firstArgs && "resume" !in firstArgs)
    assertEquals("model_reasoning_effort=high", firstArgs[firstArgs.indexOf("gpt-5") + 2])
    assertTrue(launcher.commands[0].stdin.endsWith("operator prompt") && "Execution id: wf-1" in launcher.commands[0].stdin)

    val restarted = CodexChatTransport(host, launcher, ChatStore(host, RecordingDiagnostics(), Clock.System), RecordingDiagnostics())
    val second = restarted.run(chatRequest(agent = ChatAgent("codex", null, null), firstTurn = false)).toList()

    assertEquals(listOf("resume", "t-1", "-"), launcher.commands[1].argv.takeLast(3))
    assertEquals("operator prompt", launcher.commands[1].stdin)
    assertEquals(listOf<ChatEvent>(ChatEvent.Failed(ChatFailure.ProcessFailed, "stream disconnected")), second)
  }

  // Cursor reports its chat id on every line; the next turn must resume it with permissions still forced.
  @Test
  fun `cursor forces tools, maps tool calls and resumes the reported chat`() = runTest {
    val launcher = ScriptedLauncher(
      listOf(
        lines(
          """{"type":"system","subtype":"init","session_id":"c-1"}""",
          """{"type":"tool_call","subtype":"started","call_id":"k1","tool_call":{"shellToolCall":{"args":{"command":"ls"}}},"session_id":"c-1"}""",
          """{"type":"tool_call","subtype":"completed","call_id":"k1","tool_call":{"shellToolCall":{"args":{"command":"ls"},"result":{"success":{"stdout":"a.txt"}}}},"session_id":"c-1"}""",
          """{"type":"assistant","message":{"content":[{"type":"text","text":"One file."}]},"session_id":"c-1"}""",
          """{"type":"result","subtype":"success","is_error":false,"result":"One file.","session_id":"c-1"}""",
        ),
        lines("""{"type":"result","subtype":"success","is_error":false,"result":""}"""),
      ),
    )
    val transport = CursorChatTransport(host, launcher, store, RecordingDiagnostics())

    val events = transport.run(chatRequest(agent = ChatAgent("cursor", "gpt-5", "high"))).toList()

    assertEquals(ChatEvent.TextDelta("One file."), events[events.size - 2])
    assertEquals(ChatEvent.ToolResult("k1", "{\n    \"stdout\": \"a.txt\"\n}", false), events.filterIsInstance<ChatEvent.ToolResult>().single())
    assertEquals("ls", events.filterIsInstance<ChatEvent.ToolUse>().first().call.summary)
    val firstArgs = launcher.commands[0].argv
    assertTrue("--force" in firstArgs && "--resume" !in firstArgs)
    assertEquals("gpt-5[effort=high]", firstArgs[firstArgs.indexOf("--model") + 1])

    transport.run(chatRequest(agent = ChatAgent("cursor", null, null), firstTurn = false)).toList()

    assertEquals(listOf("--resume", "c-1"), launcher.commands[1].argv.takeLast(2))
  }

  // Junie text output has no session id, so the earlier exchange must travel in the next prompt, even after a restart.
  @Test
  fun `junie streams text and carries earlier turns into the next prompt after a restart`() = runTest {
    val launcher = ScriptedLauncher(listOf(lines("First answer"), lines("Second answer")))

    val first = JunieChatTransport(host, launcher, store, RecordingDiagnostics()).run(chatRequest(agent = ChatAgent("junie", null, "high"))).toList()

    assertEquals(listOf(ChatEvent.TextDelta("First answer\n"), ChatEvent.Completed), first)
    assertTrue("--effort" !in launcher.commands[0].argv)

    val restarted = JunieChatTransport(host, launcher, ChatStore(host, RecordingDiagnostics(), Clock.System), RecordingDiagnostics())
    restarted.run(chatRequest(agent = ChatAgent("junie", null, null), firstTurn = false)).toList()

    val prompt = launcher.commands[1].argv.last()
    assertTrue("Operator:\noperator prompt\n\nYou:\nFirst answer" in prompt)
    assertTrue(prompt.endsWith("The operator's new message:\n\noperator prompt"))
  }
}
