package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `codex and cursor preserve whole message boundaries instead of inserting paragraph text`() {
    val codex = CodexStreamDecoder {}
    val cursor = CursorStreamDecoder {}
    val scripts = listOf(
      codex to listOf(
        """{"type":"item.completed","item":{"id":"m1","type":"agent_message","text":"Checking."}}""",
        """{"type":"item.completed","item":{"id":"m2","type":"agent_message","text":"Done."}}""",
      ),
      cursor to listOf(
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Checking."}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."}]}}""",
      ),
    )
    scripts.forEach { (decoder, lines) ->
      assertEquals(
        listOf(ChatEvent.MessageStarted, ChatEvent.TextDelta("Checking."), ChatEvent.MessageStarted, ChatEvent.TextDelta("Done.")),
        lines.flatMap(decoder::decode),
      )
    }
  }

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
      listOf(ChatEvent.ToolUse(call), ChatEvent.ToolResult("i1", "clean", false), ChatEvent.MessageStarted, ChatEvent.TextDelta("All clean."), ChatEvent.Completed),
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

  // Codex reports config warnings as `error` items before the turn starts; they are notices, not tool calls.
  @Test
  fun `codex error items stay out of the transcript and become the detail of a later failure`() {
    val warning =
      """{"type":"item.completed","item":{"id":"item_0","type":"error","message":"Codex is ignoring 1 unrecognized configuration setting. Check for typos or deprecated settings.\n  user (/home/sermilion/.codex/config.toml): `features.experimental_use_rmcp_client` is ignored."}}"""
    val message = "Codex is ignoring 1 unrecognized configuration setting. Check for typos or deprecated settings.\n" +
      "  user (/home/sermilion/.codex/config.toml): `features.experimental_use_rmcp_client` is ignored."

    val completed = CodexStreamDecoder {}.let { decoder ->
      listOf(
        warning,
        warning.replace("item_0", "item_1"),
        """{"type":"turn.started"}""",
        """{"type":"item.completed","item":{"id":"item_2","type":"agent_message","text":"ok"}}""",
        """{"type":"turn.completed","usage":{}}""",
      ).flatMap(decoder::decode)
    }
    assertEquals(listOf(ChatEvent.MessageStarted, ChatEvent.TextDelta("ok"), ChatEvent.Completed), completed)

    val crashed = CodexStreamDecoder {}
    assertEquals(emptyList(), crashed.decode(warning))
    assertEquals(listOf<ChatEvent>(ChatEvent.Failed(ChatFailure.ProcessFailed, message)), crashed.exited(1, "stderr tail"))
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
    assertEquals("operator prompt", launcher.commands[1].stdin)
  }

  @Test
  fun `resumed codex new-task turns receive current start-action guidance`() = runTest {
    val launcher = ScriptedLauncher(
      listOf(
        lines(
          """{"type":"thread.started","thread_id":"new-task-thread"}""",
          """{"type":"turn.completed"}""",
        ),
        lines("""{"type":"turn.completed"}"""),
      ),
    )
    val transport = CodexChatTransport(host, launcher, store, RecordingDiagnostics())
    val request = chatRequest(agent = ChatAgent("codex", null, null)).copy(
      scope = ChatScope.NewTask("/work/app", "main"),
    )

    transport.run(request).toList()
    transport.run(request.copy(firstTurn = false)).toList()

    assertTrue("\"review\"" in launcher.commands[0].stdin)
    assertTrue("\"review\"" in launcher.commands[1].stdin)
    assertTrue(launcher.commands[1].stdin.endsWith("operator prompt"))
  }

  @Test
  fun `resumed cursor new-task turns receive current start-action guidance`() = runTest {
    val launcher = ScriptedLauncher(
      listOf(
        lines(
          """{"type":"system","session_id":"new-task-chat"}""",
          """{"type":"result","subtype":"success","is_error":false}""",
        ),
        lines("""{"type":"result","subtype":"success","is_error":false}"""),
      ),
    )
    val transport = CursorChatTransport(host, launcher, store, RecordingDiagnostics())
    val request = chatRequest(agent = ChatAgent("cursor", null, null)).copy(
      scope = ChatScope.NewTask("/work/app", "main"),
    )

    transport.run(request).toList()
    transport.run(request.copy(firstTurn = false)).toList()

    assertTrue("\"review\"" in launcher.commands[0].stdin)
    assertTrue("\"review\"" in launcher.commands[1].stdin)
    assertTrue(launcher.commands[1].stdin.endsWith("operator prompt"))
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

  @Test
  fun `junie keeps a stopped turn in the next prompt`() = runTest {
    val launcher = ScriptedLauncher(listOf(lines("Partial answer"), lines("Second answer")))
    val transport = JunieChatTransport(host, launcher, store, RecordingDiagnostics())

    transport.run(chatRequest(agent = ChatAgent("junie", null, null))).first()
    transport.run(chatRequest(agent = ChatAgent("junie", null, null), firstTurn = false)).toList()

    assertTrue("Operator:\noperator prompt\n\nYou:\nPartial answer" in launcher.commands[1].argv.last())
  }

  // Codex reads images from files, so each pasted image must reach it as a real absolute path, and leave with the session.
  @Test
  fun `codex gets each pasted image as an absolute file path and end removes the files`() = runTest {
    val sessionId = "images-" + UUID.randomUUID()
    val launcher = ScriptedLauncher(listOf(lines("""{"type":"turn.completed"}""")))
    val transport = CodexChatTransport(host, launcher, store, RecordingDiagnostics())
    val images = listOf(ChatImage(byteArrayOf(1, 2, 3), "image/png"), ChatImage(byteArrayOf(4, 5), "image/jpeg"))
    try {
      transport.run(chatRequest(agent = ChatAgent("codex", null, null)).copy(sessionId = sessionId, images = images)).toList()

      val stdin = launcher.commands.single().stdin
      val footer = stdin.substringAfter("operator prompt\n\nAttached images (open each file to view it):\n").lines()
      assertEquals(2, footer.size)
      val paths = footer.mapIndexed { index, line ->
        assertTrue(line.startsWith("[Image #${index + 1}] "))
        Path.of(line.removePrefix("[Image #${index + 1}] "))
      }
      paths.forEachIndexed { index, path ->
        assertTrue(path.isAbsolute)
        assertEquals(path.toRealPath(), path)
        assertContentEquals(images[index].bytes, Files.readAllBytes(path))
      }
      assertTrue(paths[0].toString().endsWith(".png") && paths[1].toString().endsWith(".jpg"))

      transport.end(sessionId)

      assertFalse(Files.exists(chatImageDirectory(sessionId)))
    } finally {
      transport.end(sessionId)
    }
  }

  // Cursor reads the footer from stdin and Junie from argv; Junie's saved history must hold the typed text, not temp paths.
  @Test
  fun `cursor and junie carry the image footer and junie history keeps only the typed text`() = runTest {
    val sessionId = "images-" + UUID.randomUUID()
    val image = ChatImage(byteArrayOf(9, 8, 7), "image/png")
    val cursorLauncher = ScriptedLauncher(listOf(lines("""{"type":"result","subtype":"success","is_error":false}""")))
    val junieLauncher = ScriptedLauncher(listOf(lines("Seen"), lines("Next")))
    val cursor = CursorChatTransport(host, cursorLauncher, store, RecordingDiagnostics())
    val junie = JunieChatTransport(host, junieLauncher, store, RecordingDiagnostics())
    try {
      cursor.run(chatRequest(agent = ChatAgent("cursor", null, null)).copy(sessionId = sessionId, images = listOf(image))).toList()
      junie.run(chatRequest(agent = ChatAgent("junie", null, null)).copy(sessionId = sessionId, images = listOf(image))).toList()

      listOf(cursorLauncher.commands.single().stdin, junieLauncher.commands.single().argv.last()).forEach { text ->
        val path = Path.of(text.substringAfter("[Image #1] ").lines().first())
        assertTrue(path.isAbsolute)
        assertContentEquals(image.bytes, Files.readAllBytes(path))
      }

      junie.run(chatRequest(agent = ChatAgent("junie", null, null), firstTurn = false).copy(sessionId = sessionId)).toList()

      val prompt = junieLauncher.commands[1].argv.last()
      assertTrue("Operator:\noperator prompt\n\nYou:" in prompt)
      assertFalse("Attached images" in prompt)
    } finally {
      cursor.end(sessionId)
    }
  }

  // A pasted image that cannot be saved must fail the turn before any agent starts, and not poison the next turn.
  @Test
  fun `an image that cannot be saved fails the turn without starting a process`() = runTest {
    val sessionId = "images-" + UUID.randomUUID()
    val launcher = ScriptedLauncher(listOf(lines("Fine")))
    val junie = JunieChatTransport(host, launcher, store, RecordingDiagnostics())
    Files.createDirectories(chatImageRoot())
    val blocker = Files.createFile(chatImageDirectory(sessionId))
    try {
      val failed = junie.run(
        chatRequest(agent = ChatAgent("junie", null, null)).copy(sessionId = sessionId, images = listOf(ChatImage(byteArrayOf(1), "image/png"))),
      ).toList()

      val failure = failed.single() as ChatEvent.Failed
      assertEquals(ChatFailure.ProcessFailed, failure.failure)
      assertTrue(failure.detail.orEmpty().startsWith("Could not save a pasted image: "))
      assertTrue(launcher.commands.isEmpty())

      junie.run(chatRequest(agent = ChatAgent("junie", null, null)).copy(sessionId = sessionId)).toList()

      assertEquals(1, launcher.commands.size)
      assertFalse("Attached images" in launcher.commands.single().argv.last())
    } finally {
      Files.deleteIfExists(blocker)
    }
  }

  // The temp root can be shared, so a link planted as the session directory must be removed, never followed into its target.
  @Test
  fun `end removes a symlinked session directory without deleting the link target`() = runTest {
    val sessionId = "images-" + UUID.randomUUID()
    val outside = createTempDirectory("chat-images-outside")
    val kept = Files.write(outside.resolve("kept.txt"), byteArrayOf(1))
    Files.createDirectories(chatImageRoot())
    val link = Files.createSymbolicLink(chatImageDirectory(sessionId), outside)
    try {
      CodexChatTransport(host, ScriptedLauncher(emptyList()), store, RecordingDiagnostics()).end(sessionId)

      assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.exists(kept))
    } finally {
      Files.deleteIfExists(link)
      outside.toFile().deleteRecursively()
    }
  }
}
