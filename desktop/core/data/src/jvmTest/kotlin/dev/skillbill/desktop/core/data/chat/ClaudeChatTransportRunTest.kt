package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeChatTransportRunTest {
  // One scripted claude process: the test reads what the app writes to stdin and decides what stdout prints.
  private class FakeClaude(
    val holdStop: CompletableDeferred<Unit>? = null,
    val loginOutput: List<String> = emptyList(),
  ) : LiveChatProcessLauncher {
    val opened: MutableList<ChatCommand> = Collections.synchronizedList(mutableListOf())
    val stdin = Channel<String>(Channel.UNLIMITED)
    val stdout = Channel<ChatProcessOutput>(Channel.UNLIMITED)
    val stopped = CompletableDeferred<Unit>()
    val stopHeld = CompletableDeferred<Unit>()

    override fun open(command: ChatCommand, input: ReceiveChannel<String>): Flow<ChatProcessOutput> = channelFlow {
      opened += command
      launch {
        for (line in input) {
          if (INITIALIZE_REQUEST in line) {
            loginOutput.forEach { stdout.send(ChatProcessOutput.Line(it)) }
            stdout.send(ChatProcessOutput.Line(INITIALIZE_SUCCESS))
          } else {
            stdin.send(line)
          }
        }
      }
      try {
        for (output in stdout) send(output)
      } finally {
        stopped.complete(Unit)
        stopHeld.complete(Unit)
        withContext(NonCancellable) { holdStop?.await() }
      }
    }

    override fun openAcknowledged(
      command: ChatCommand,
      input: ReceiveChannel<ChatProcessInput>,
    ): Flow<ChatProcessOutput> = channelFlow {
      opened += command
      launch {
        for (message in input) {
          try {
            if (INITIALIZE_REQUEST in message.line) {
              loginOutput.forEach { stdout.send(ChatProcessOutput.Line(it)) }
              stdout.send(ChatProcessOutput.Line(INITIALIZE_SUCCESS))
            } else {
              stdin.send(message.line)
            }
            message.written.complete(true)
          } catch (failure: Throwable) {
            message.written.complete(false)
            throw failure
          }
        }
      }
      try {
        for (output in stdout) send(output)
      } finally {
        stopped.complete(Unit)
        stopHeld.complete(Unit)
        withContext(NonCancellable) { holdStop?.await() }
      }
    }

    suspend fun nextMessage(): Pair<String, String> {
      val message = Json.parseToJsonElement(stdin.receive()).jsonObject
      return message.getValue("uuid").jsonPrimitive.content to
        message.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
    }

    suspend fun print(vararg lines: String) = lines.forEach { stdout.send(ChatProcessOutput.Line(it)) }

    companion object {
      const val INITIALIZE_REQUEST = "\"subtype\":\"initialize\""
      const val INITIALIZE_SUCCESS =
        """{"type":"control_response","response":{"subtype":"success","request_id":"skill-bill-init"}}"""
    }
  }

  private fun TestScope.transport(claude: FakeClaude, diagnostics: RecordingDiagnostics = RecordingDiagnostics()) = ClaudeChatTransport(
    HostEnvironment(mapOf("SHELL" to "/bin/bash"), "/home/operator", ""),
    FakeAppSettingsRepository(),
    claude,
    { _, root, _, _, writeLine, stopProcess, onCard ->
      ChatApprovalOpening.Opened(ClaudeChatApproval(root, writeLine, stopProcess, diagnostics, onCard))
    },
    diagnostics,
    ApplicationCoroutineScope(backgroundScope),
  )

  // A background build the agent started used to die when its turn's process exited, and nothing reported back.
  @Test
  fun `the process outlives the turn, a reply the agent starts on its own reaches unprompted, and the next turn reuses it`() = runTest {
    val claude = FakeClaude(loginOutput = listOf("Agent pid 4242"))
    val diagnostics = RecordingDiagnostics()
    val transport = transport(claude, diagnostics)
    val unprompted = async(start = CoroutineStart.UNDISPATCHED) { transport.unprompted("session-1").take(2).toList() }

    val first = async { transport.run(chatRequest()).toList() }
    val (uuid, text) = claude.nextMessage()
    claude.print(
      """{"type":"system","subtype":"init"}""",
      replay(uuid),
      textDelta("Started the build"),
      """{"type":"result","is_error":false,"result":"Started the build"}""",
    )
    assertEquals(listOf(ChatEvent.TextDelta("Started the build"), ChatEvent.Completed), first.await())
    assertEquals("operator prompt", text)

    claude.print(
      """{"type":"system","subtype":"task_notification","task_id":"b1","status":"completed"}""",
      textDelta("The build passed"),
      """{"type":"result","is_error":false,"result":"The build passed"}""",
    )
    assertEquals(listOf(ChatEvent.TextDelta("The build passed"), ChatEvent.Completed), unprompted.await())

    val second = async { transport.run(chatRequest(firstTurn = false)).toList() }
    val (secondUuid, _) = claude.nextMessage()
    claude.print(replay(secondUuid), """{"type":"result","is_error":false,"result":""}""")
    assertEquals(listOf<ChatEvent>(ChatEvent.Completed), second.await())
    assertEquals(1, claude.opened.size)
    assertEquals("/work/skill-bill", claude.opened.single().workingDirectory)
    assertEquals(listOf("chat.login_output_skipped"), diagnostics.categories)
    assertFalse(claude.stopped.isCompleted)

    transport.end("session-1")
    claude.stopped.await()
  }

  // A live process keeps the directory it started in; reusing it after the goal's worktree moved sent the turn to a dead path.
  @Test
  fun `a turn in another directory stops the live process and resumes the session in a new one there`() = runTest {
    val claude = FakeClaude()
    val transport = transport(claude)
    val first = async { transport.run(chatRequest()).toList() }
    val (uuid, _) = claude.nextMessage()
    claude.print(replay(uuid), """{"type":"result","is_error":false,"result":""}""")
    first.await()

    val moved = chatRequest(firstTurn = false)
    val second = async { transport.run(moved.copy(scope = (moved.scope as ChatScope.Execution).copy(repositoryRoot = "/work/main"))).toList() }
    claude.stopped.await()
    val (secondUuid, _) = claude.nextMessage()
    claude.print(replay(secondUuid), """{"type":"result","is_error":false,"result":""}""")
    assertEquals(listOf<ChatEvent>(ChatEvent.Completed), second.await())

    assertEquals(listOf("/work/skill-bill", "/work/main"), claude.opened.map { it.workingDirectory })
    assertTrue("--resume" in claude.opened[1].argv)

    transport.end("session-1")
  }

  // Closing a tab or `/clear` cancels the turn; the agent and its commands must not keep running unseen.
  @Test
  fun `cancelling a turn before its reply stops the process`() = runTest {
    val claude = FakeClaude()
    val transport = transport(claude)

    val turn = async { transport.run(chatRequest()).toList() }
    claude.nextMessage()
    turn.cancel()

    claude.stopped.await()
  }

  @Test
  fun `the next turn waits for a stopped process before opening claude again`() = runTest {
    val holdStop = CompletableDeferred<Unit>()
    val claude = FakeClaude(holdStop)
    val diagnostics = RecordingDiagnostics()
    val transport = transport(claude, diagnostics)

    val first = async { transport.run(chatRequest()).toList() }
    claude.nextMessage()
    first.cancelAndJoin()
    claude.stopHeld.await()

    val second = async { transport.run(chatRequest(firstTurn = false)).toList() }
    testScheduler.runCurrent()
    assertFalse("chat.stop_wait_expired" in diagnostics.categories)
    assertEquals(1, claude.opened.size)

    testScheduler.advanceUntilIdle()
    assertTrue("chat.stop_wait_expired" in diagnostics.categories)

    claude.nextMessage()
    assertEquals(2, claude.opened.size)
    assertTrue("--resume" in claude.opened[1].argv)

    holdStop.complete(Unit)
    second.cancel()
  }

  // Through a login shell a missing agent binary exits 127; a process that never started points at the worktree or the shell.
  @Test
  fun `a process that never started fails the turn as a process failure, not a missing binary`() = runTest {
    val claude = FakeClaude()
    claude.stdout.send(ChatProcessOutput.StartFailed("The working directory /gone does not exist."))

    val events = transport(claude).run(chatRequest()).toList()

    assertEquals(listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, "The working directory /gone does not exist.")), events)
  }

  // Claude takes images as base64 content blocks; text-only turns must keep the plain string content they always had.
  @Test
  fun `an image turn sends content blocks and a text-only turn still sends a string`() = runTest {
    val claude = FakeClaude()
    val transport = transport(claude)

    val first = async { transport.run(chatRequest().copy(images = listOf(ChatImage(byteArrayOf(1, 2, 3), "image/png")))).toList() }
    val line = claude.stdin.receive()
    val uuid = Json.parseToJsonElement(line).jsonObject.getValue("uuid").jsonPrimitive.content
    assertEquals(
      """{"type":"user","uuid":"$uuid","message":{"role":"user","content":[{"type":"text","text":"operator prompt"},""" +
        """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AQID"}}]}}""",
      line,
    )
    claude.print(replay(uuid), """{"type":"result","is_error":false,"result":""}""")
    assertEquals(listOf<ChatEvent>(ChatEvent.Completed), first.await())

    val second = async { transport.run(chatRequest(firstTurn = false)).toList() }
    val (secondUuid, text) = claude.nextMessage()
    claude.print(replay(secondUuid), """{"type":"result","is_error":false,"result":""}""")
    assertEquals(listOf<ChatEvent>(ChatEvent.Completed), second.await())
    assertEquals("operator prompt", text)

    transport.end("session-1")
    claude.stopped.await()
  }

  // A user frame written before the provider accepted host permission control would run without approval cards.
  @Test
  fun `a turn's user frame is held until the provider answers initialize`() {
    val diagnostics = RecordingDiagnostics()
    val session = ClaudeLiveSession(ChatProcessBinding(ChatPermissionMode.Act, ""), "/work/skill-bill", diagnostics) {}
    session.approval = ClaudeChatApproval("/work/skill-bill", { true }, {}, diagnostics, onCard = {})

    session.send("uuid-1", "operator prompt", emptyList())
    assertTrue(session.input.tryReceive().isFailure)

    session.accept(ChatProcessOutput.Line(FakeClaude.INITIALIZE_SUCCESS))
    val frame = Json.parseToJsonElement(session.input.tryReceive().getOrThrow().line).jsonObject
    assertEquals("uuid-1", frame.getValue("uuid").jsonPrimitive.content)
    assertTrue(session.initializedSignal.isCompleted)
  }

  // Claude streams each message between tool calls as its own text block; joined without a break they read as one run-on paragraph.
  @Test
  fun `a new text block after a tool call starts a new paragraph, but a new turn does not`() = runTest {
    val claude = FakeClaude()
    val transport = transport(claude)

    val first = async { transport.run(chatRequest()).toList() }
    val (uuid, _) = claude.nextMessage()
    claude.print(
      replay(uuid),
      textBlockStart(),
      textDelta("Probing each take."),
      """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"bash-1","name":"Bash","input":{}}]}}""",
      """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"bash-1","content":"ok"}]}}""",
      textBlockStart(),
      textDelta("Take 1 hit the cap."),
      """{"type":"result","is_error":false,"result":""}""",
    )
    assertEquals(
      listOf(
        ChatEvent.TextDelta("Probing each take."),
        ChatEvent.ToolUse(ChatToolCall("bash-1", "Bash", "{}", null)),
        ChatEvent.ToolResult("bash-1", "ok", false),
        ChatEvent.TextDelta("\n\n"),
        ChatEvent.TextDelta("Take 1 hit the cap."),
        ChatEvent.Completed,
      ),
      first.await(),
    )

    val second = async { transport.run(chatRequest(firstTurn = false)).toList() }
    val (secondUuid, _) = claude.nextMessage()
    claude.print(replay(secondUuid), textBlockStart(), textDelta("Done."), """{"type":"result","is_error":false,"result":""}""")
    assertEquals(listOf(ChatEvent.TextDelta("Done."), ChatEvent.Completed), second.await())

    transport.end("session-1")
    claude.stopped.await()
  }

  private fun replay(uuid: String) =
    """{"type":"user","uuid":"$uuid","isReplay":true,"message":{"role":"user","content":"operator prompt"}}"""

  private fun textBlockStart() =
    """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}"""

  private fun textDelta(text: String) = """{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"$text"}}}"""
}
