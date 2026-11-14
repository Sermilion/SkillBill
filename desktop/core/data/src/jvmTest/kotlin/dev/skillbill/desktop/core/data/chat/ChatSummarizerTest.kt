package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatTitleUpdate
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
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import java.io.File
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
  private val acpCommands = mutableListOf<ChatCommand>()

  /** Answers every call with [reply]; [during] runs while the call is in flight. */
  private var reply: (ChatCommand) -> List<ChatProcessOutput> = { claudeReply("About CI.") }
  private var during: suspend () -> Unit = {}

  /** The ACP frames an opencode label call receives, in order. */
  private var acpScript: List<BoundedProtocolInput> = emptyList()
  private var acpSent = mutableListOf<String>()

  private val launcher = ChatProcessLauncher { command ->
    flow {
      commands += command
      during()
      reply(command).forEach { emit(it) }
    }
  }

  private val protocolLauncher = BoundedProtocolLauncher { command ->
    acpCommands += command
    BoundedProcessStart.Started(object : BoundedProtocolConnection {
      private val inputs = ArrayDeque(acpScript)
      override suspend fun sendLine(line: String): Boolean = acpSent.add(line)
      override suspend fun receive(): BoundedProtocolInput = inputs.removeFirstOrNull() ?: BoundedProtocolInput.Exited
      override fun beginResponseBudget() {}
      override fun endResponseBudget() {}
      override suspend fun close() {}
    })
  }

  private fun TestScope.summarizer() =
    ChatSummarizer(host, FakeAppSettingsRepository(), launcher, protocolLauncher, store, diagnostics, ApplicationCoroutineScope(backgroundScope))
      .also { it.dispatcher = StandardTestDispatcher(testScheduler) }

  private fun entry(session: String, agent: String, at: Long) = ChatHistoryEntry(
    session, "wf-1", ChatAgent(agent, "opus", "high", null), Instant.fromEpochMilliseconds(at), "why is $session blocked?",
    listOf(ChatHistoryLine(ChatHistoryRole.Operator, "why is $session blocked?"), ChatHistoryLine(ChatHistoryRole.Agent, "CI failed.")),
  )

  // A summary call that can edit files, run tools, resume the chat or bill a selected model costs the operator real work.
  @Test
  fun `each supported agent uses native bypass in the summaries directory on its default model`() = runTest {
    val summarizer = summarizer()
    listOf("claude", "codex", "cursor").forEachIndexed { index, agent -> store.saveChat(entry(agent, agent, index + 1L)) }
    // A devin chat with no native session or checkout is recovery-only, so give this one its real shape.
    val devin = entry("devin", "devin", 4L).copy(executionCheckoutRoot = createTempDirectory("devin-root").toString())
    assertTrue(store.saveDevinSession("devin", "native-42", devin))
    reply = { command -> agentReply(command, "About CI.") }

    listOf("claude", "codex", "cursor", "devin").forEach(summarizer::request)
    runCurrent()

    val forbidden = listOf(
      "--session-id", "--resume", "--append-system-prompt", "--system-prompt", "--model", "--approve-mcps",
    )
    assertEquals(4, commands.size)
    commands.forEach { command ->
      assertTrue(forbidden.none { it in command.argv }, "${command.argv}")
      assertEquals(File(host.userHome, CHAT_SUMMARY_PATH).path, command.workingDirectory)
    }
    assertTrue(listOf("--dangerously-bypass-approvals-and-sandbox", "--ephemeral").all { it in commands.first { "codex" in it.argv }.argv })
    assertTrue(listOf("--force", "--trust", "--sandbox", "disabled").all { it in commands.first { "agent" in it.argv }.argv })
    // A label call gets an empty cursor config home: no MCP servers or workspace state load and stall it.
    assertEquals(
      File(File(host.userHome, CHAT_SUMMARY_PATH), CURSOR_LABEL_HOME).path,
      commands.first { "agent" in it.argv }.environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR],
    )
    assertTrue(listOf("--dangerously-skip-permissions", "--tools", "", "--no-session-persistence").all { it in commands.first { "claude" in it.argv }.argv })
    assertTrue(listOf("-p", "--respect-workspace-trust", "false").all { it in commands.first { "devin" in it.argv }.argv })
    listOf("claude", "cursor").forEach { agent ->
      val argv = commands.first { agent in it.argv || (agent == "cursor" && "agent" in it.argv) }.argv
      val outputFormat = argv.indexOf("--output-format")
      assertTrue(outputFormat >= 0 && argv.getOrNull(outputFormat + 1) == "stream-json", "$argv")
    }
    commands.forEach { command ->
      val outputFormat = command.argv.indexOf("--output-format")
      if (outputFormat >= 0) assertTrue(command.argv.getOrNull(outputFormat + 1) != "text", "${command.argv}")
    }
    assertEquals("About CI.", store.savedFor("devin")?.summary)
  }

  // `opencode run` closes stdout before the reply arrives, so the label call goes through one ACP turn instead.
  @Test
  fun `opencode labels run through one acp prompt on its anthropic small model`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("oc1", "opencode", 1).copy(agent = ChatAgent("opencode", null, null)))
    acpScript = listOf(
      acpLine("""{"id":1,"result":{"protocolVersion":1}}"""),
      acpLine("""{"id":2,"result":{"sessionId":"acp-9"}}"""),
      acpLine("""{"id":3,"result":{}}"""),
      acpLine("""{"method":"session/update","params":{"sessionId":"acp-9","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Blocked CI"}}}}"""),
      acpLine("""{"id":4,"result":{"stopReason":"end_turn"}}"""),
    )

    summarizer.request("oc1")
    runCurrent()

    assertEquals(0, commands.size)
    assertEquals(1, acpCommands.size)
    assertTrue("acp" in acpCommands.single().argv)
    assertEquals(File(host.userHome, CHAT_SUMMARY_PATH).path, acpCommands.single().workingDirectory)
    assertEquals(listOf("initialize", "session/new", "session/set_config_option", "session/prompt"),
      acpSent.map { """"method":"([^"]+)"""".toRegex().find(it)!!.groupValues[1] })
    assertTrue(acpSent[2].contains(""""value":"$OPENCODE_LABEL_MODEL""""))
    assertEquals("Blocked CI", store.savedFor("oc1")?.summary)
  }

  // A multi-line or quoted reply makes an unreadable row, and stamping the newer save hides turns from re-summarizing.
  @Test
  fun `the first reply line becomes the chat title stamped with the snapshot time`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("s1", "claude", 1))
    reply = { command -> agentReply(command, "\n- \"Fix flaky upload test\"\nMore text") }
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

    assertEquals("Fix flaky upload test", store.savedFor("s1")?.summary)
    assertEquals("s1", store.staleForSummary("s1")?.sessionId)
    assertEquals(listOf("s1"), updates)
  }

  // A title is written once: a request on a titled chat launches nothing and emits nothing.
  @Test
  fun `a title request writes the tab label once and reports it`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("t1", "claude", 1))
    reply = { claudeReply("Fix flaky test") }
    val updates = mutableListOf<ChatTitleUpdate>()
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { summarizer.titleUpdates.collect { updates += it } }

    summarizer.requestTitle("t1")
    summarizer.requestTitle("t1")
    runCurrent()

    assertEquals(1, commands.size)
    assertEquals("Fix flaky test", store.savedFor("t1")?.generatedTitle)
    assertEquals(listOf(ChatTitleUpdate("t1", "Fix flaky test")), updates)

    summarizer.requestTitle("t1")
    runCurrent()

    assertEquals(1, commands.size)
    assertEquals(1, updates.size)
  }

  // A tab label that arrives lowercase stays lowercase in the UI.
  @Test
  fun `a generated title starts with a capital letter`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("t2", "claude", 1))
    reply = { claudeReply("fix flaky test") }

    summarizer.requestTitle("t2")
    runCurrent()

    assertEquals("Fix flaky test", store.savedFor("t2")?.generatedTitle)
  }

  // A title queued behind a hung or stale summary call otherwise never runs in the chat's own window.
  @Test
  fun `a title request does not wait behind an in-flight summary call`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("slow", "claude", 1))
    store.saveChat(entry("t1", "claude", 2))
    during = { if (commands.size == 1) awaitCancellation() }
    val updates = mutableListOf<ChatTitleUpdate>()
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { summarizer.titleUpdates.collect { updates += it } }

    summarizer.request("slow")
    summarizer.requestTitle("t1")
    runCurrent()

    assertEquals("About CI.", store.savedFor("t1")?.generatedTitle)
    assertEquals(listOf(ChatTitleUpdate("t1", "About CI.")), updates)
  }

  // A title is not a summary: each request kind writes only its own field.
  @Test
  fun `title and summary requests write their own fields`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("both", "claude", 1))
    reply = { claudeReply("Fix flaky test") }

    summarizer.requestTitle("both")
    runCurrent()

    assertEquals("Fix flaky test", store.savedFor("both")?.generatedTitle)
    assertEquals(null, store.savedFor("both")?.summary)
    assertEquals("both", store.staleForSummary("both")?.sessionId)
  }

  // An overlong title or summary fills the tab or row with a sentence instead of a label.
  @Test
  fun `overlong generated titles stop at a whole word within eighty characters`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("long-title", "claude", 1))
    reply = { claudeReply("Investigate failing desktop builds and fix dependency resolution across all development environments") }

    summarizer.request("long-title")
    runCurrent()

    assertEquals("Investigate failing desktop builds and fix dependency resolution across all", store.savedFor("long-title")?.summary)
  }

  // A failed call that saves its partial reply as the summary, or an unsupported agent that launches, costs the operator.
  @Test
  fun `a failed turn and an unsupported agent write nothing and only the supported one launches`() = runTest {
    val summarizer = summarizer()
    store.saveChat(entry("fails", "claude", 1))
    store.saveChat(entry("retired", "retired-agent", 2))
    reply = { listOf(claudeDelta("Partial answer about"), ChatProcessOutput.Exited(1, "rate limited")) }

    summarizer.request("fails")
    summarizer.request("retired")
    runCurrent()

    assertEquals(1, commands.size)
    assertEquals(null, store.savedFor("fails")?.summary)
    assertEquals(null, store.savedFor("retired")?.summary)
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

  private fun acpLine(frame: String) = BoundedProtocolInput.Line(frame)

  private fun devinReply(text: String): List<ChatProcessOutput> = listOf(
    ChatProcessOutput.Line(text),
    ChatProcessOutput.Exited(0, ""),
  )

  private fun codexReply(text: String): List<ChatProcessOutput> = listOf(
    ChatProcessOutput.Line("""{"type":"item.completed","item":{"type":"agent_message","text":"$text"}}"""),
    ChatProcessOutput.Line("""{"type":"turn.completed"}"""),
    ChatProcessOutput.Exited(0, ""),
  )

  private fun agentReply(command: ChatCommand, text: String): List<ChatProcessOutput> = when {
    "codex" in command.argv -> codexReply(text)
    "agent" in command.argv -> cursorReply(text)
    "devin" in command.argv -> devinReply(text)
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
