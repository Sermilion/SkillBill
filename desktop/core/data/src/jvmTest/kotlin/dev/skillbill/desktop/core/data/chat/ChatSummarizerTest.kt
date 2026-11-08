package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ChatSummarizerTest {
  private val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")
  private val diagnostics = RecordingDiagnostics()
  private val store = ChatStore(host, diagnostics, Clock.System)
  private val commands = mutableListOf<ChatCommand>()

  /** Answers every call with [reply]; [during] runs while the call is in flight. */
  private var reply: (ChatCommand) -> List<ChatProcessOutput> = { claudeReply("About CI.") }
  private var during: suspend () -> Unit = {}

  private val launcher = ChatProcessLauncher { command ->
    flow {
      commands += command
      during()
      reply(command).forEach { emit(it) }
    }
  }

  private fun TestScope.summarizer() =
    ChatSummarizer(host, FakeAppSettingsRepository(), launcher, store, diagnostics, ApplicationCoroutineScope(backgroundScope))
      .also { it.dispatcher = StandardTestDispatcher(testScheduler) }

  private fun entry(session: String, agent: String, at: Long) = ChatHistoryEntry(
    session, "wf-1", ChatAgent(agent, "opus", "high", null), Instant.fromEpochMilliseconds(at), "why is $session blocked?",
    listOf(ChatHistoryLine(ChatHistoryRole.Operator, "why is $session blocked?"), ChatHistoryLine(ChatHistoryRole.Agent, "CI failed.")),
  )

  // A summary call that can edit files, run tools, resume the chat or bill the reasoning model costs the operator real work.
  @Test
  fun `each agent runs read-only on its cheap model in the summaries directory`() = runTest {
    val summarizer = summarizer()
    listOf("claude", "codex", "cursor").forEachIndexed { index, agent -> store.saveChat(entry(agent, agent, index + 1L)) }
    reply = { command -> agentReply(command, "About CI.") }

    listOf("claude", "codex", "cursor").forEach(summarizer::request)
    runCurrent()

    val forbidden = listOf(
      "--dangerously-skip-permissions", "--dangerously-bypass-approvals-and-sandbox", "--force", "--yolo", "--approve-mcps",
      "--session-id", "--resume", "--append-system-prompt", "--system-prompt",
    )
    val expected = mapOf("claude" to "haiku", "codex" to "gpt-6-luna", "agent" to "gpt-5.4-nano-none")
    assertEquals(3, commands.size)
    commands.forEach { command ->
      val binary = expected.keys.first { it in command.argv }
      assertTrue(expected.getValue(binary) in command.argv, "${command.argv}")
      assertTrue(forbidden.none { it in command.argv }, "${command.argv}")
      assertTrue(command.workingDirectory.endsWith(CHAT_SUMMARY_PATH))
    }
    assertTrue(listOf("--sandbox", "read-only", "--ephemeral").all { it in commands.first { "codex" in it.argv }.argv })
    assertTrue(listOf("--mode", "ask").all { it in commands.first { "agent" in it.argv }.argv })
    assertTrue(listOf("--tools", "", "--no-session-persistence").all { it in commands.first { "claude" in it.argv }.argv })
    listOf("claude", "cursor").forEach { agent ->
      val argv = commands.first { agent in it.argv || (agent == "cursor" && "agent" in it.argv) }.argv
      val outputFormat = argv.indexOf("--output-format")
      assertTrue(outputFormat >= 0 && argv.getOrNull(outputFormat + 1) == "stream-json", "$argv")
    }
    commands.forEach { command ->
      val outputFormat = command.argv.indexOf("--output-format")
      if (outputFormat >= 0) assertTrue(command.argv.getOrNull(outputFormat + 1) != "text", "${command.argv}")
    }
  }

  // A multi-line or quoted reply makes an unreadable row, and stamping the newer save hides turns from re-summarizing.
  @Test
  fun `the first reply line becomes the summary stamped with the snapshot time`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("s1", "claude", 1))
    reply = { command -> agentReply(command, "\n- \"Fixes the flaky upload test.\"\nMore text") }
    during = {
      val nextTurn = entry("s1", "claude", 2)
      store.saveChat(nextTurn.copy(lines = nextTurn.lines + listOf(
        ChatHistoryLine(ChatHistoryRole.Operator, "what changed?"),
        ChatHistoryLine(ChatHistoryRole.Agent, "The deployment is fixed."),
      )))
    }
    val updates = mutableListOf<String>()
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { summarizer.updates.collect { updates += it } }

    summarizer.request("s1")
    runCurrent()

    assertEquals("Fixes the flaky upload test.", store.savedFor("s1")?.summary)
    assertEquals("s1", store.staleForSummary("s1")?.sessionId)
    assertEquals(listOf("s1"), updates)
  }

  // A failed call that saves its partial reply as the summary, or a Junie chat that launches an agent with no cheap model, both cost the operator.
  @Test
  fun `a failed turn and an unsupported agent write nothing and only the supported one launches`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("fails", "claude", 1))
    store.saveChat(entry("junie", "junie", 2))
    reply = { listOf(claudeDelta("Partial answer about"), ChatProcessOutput.Exited(1, "rate limited")) }

    summarizer.request("fails")
    summarizer.request("junie")
    runCurrent()

    assertEquals(1, commands.size)
    assertEquals(null, store.savedFor("fails")?.summary)
    assertEquals(null, store.savedFor("junie")?.summary)
    assertTrue("chat.summary_failed" in diagnostics.categories)
    assertTrue("chat.summary_unsupported_agent" in diagnostics.categories)
  }

  @Test
  fun `login shell output is skipped before every supported agent summary`() = runTest {
    val summarizer = summarizer()
    listOf("claude", "cursor", "codex").forEachIndexed { index, agent -> store.saveChat(entry(agent, agent, index + 1L)) }
    reply = { command ->
      listOf(ChatProcessOutput.Line("Now using node v22 (npm v10)"), ChatProcessOutput.Line("")) + agentReply(command, "About CI.")
    }

    listOf("claude", "cursor", "codex").forEach(summarizer::request)
    runCurrent()

    listOf("claude", "cursor", "codex").forEach { agent -> assertEquals("About CI.", store.savedFor(agent)?.summary) }
    assertTrue("chat.login_output_skipped" in diagnostics.categories)
  }

  // A hung agent with no timeout, or one throwing call that ends the worker, leaves every later chat on its fallback title.
  @Test
  fun `a throwing call and a call past the timeout write nothing and the queue keeps running`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("throws", "claude", 1))
    store.saveChat(entry("hangs", "claude", 2))
    store.saveChat(entry("next", "claude", 3))
    during = {
      when (commands.size) {
        1 -> error("launcher broke")
        2 -> awaitCancellation()
        else -> Unit
      }
    }

    listOf("throws", "hangs", "next").forEach(summarizer::request)
    advanceTimeBy(121.seconds)
    runCurrent()

    assertEquals(null, store.savedFor("throws")?.summary)
    assertEquals(null, store.savedFor("hangs")?.summary)
    assertEquals("About CI.", store.savedFor("next")?.summary)
    assertEquals(2, diagnostics.categories.count { it == "chat.summary_failed" })
  }

  // Re-summarizing every chat at each launch bills the operator for nothing.
  @Test
  fun `start summarizes stale chats newest first once`() = runTest {
    val summarizer = summarizer()
    (1L..4L).forEach { store.saveChat(entry("s$it", "claude", it)) }
    store.saveSummary("s4", "Done.", Instant.fromEpochMilliseconds(4))

    summarizer.start()
    runCurrent()
    val nextTurn = entry("s1", "claude", 5)
    store.saveChat(nextTurn.copy(lines = nextTurn.lines + listOf(
      ChatHistoryLine(ChatHistoryRole.Operator, "what changed?"),
      ChatHistoryLine(ChatHistoryRole.Agent, "The deployment is fixed."),
    )))
    summarizer.start()
    runCurrent()

    assertEquals(listOf("s3", "s2", "s1"), commands.map { command -> listOf("s1", "s2", "s3").single { "why is $it blocked?" in command.stdin } })
    assertEquals(listOf("About CI.", "About CI.", "About CI."), listOf("s3", "s2", "s1").map { store.savedFor(it)?.summary })
    assertEquals(listOf("s1"), store.staleSummaries(20).map { it.sessionId })
  }

  private fun codexReply(text: String): List<ChatProcessOutput> = listOf(
    ChatProcessOutput.Line("""{"type":"item.completed","item":{"type":"agent_message","text":"$text"}}"""),
    ChatProcessOutput.Line("""{"type":"turn.completed"}"""),
    ChatProcessOutput.Exited(0, ""),
  )

  private fun agentReply(command: ChatCommand, text: String): List<ChatProcessOutput> = when {
    "codex" in command.argv -> codexReply(text)
    "agent" in command.argv -> cursorReply(text)
    else -> claudeReply(text)
  }

  private fun claudeReply(text: String): List<ChatProcessOutput> = listOf(
    claudeDelta(text),
    ChatProcessOutput.Line("""{"type":"result","is_error":false}"""),
    ChatProcessOutput.Exited(0, ""),
  )

  private fun claudeDelta(text: String): ChatProcessOutput.Line {
    val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    return ChatProcessOutput.Line("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"$escaped"}}}""")
  }

  private fun cursorReply(text: String): List<ChatProcessOutput> {
    val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    return listOf(
      ChatProcessOutput.Line("""{"type":"assistant","message":{"content":[{"type":"text","text":"$escaped"}]}}"""),
      ChatProcessOutput.Line("""{"type":"result","is_error":false}"""),
      ChatProcessOutput.Exited(0, ""),
    )
  }
}
