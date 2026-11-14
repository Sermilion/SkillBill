package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatStopSettlement
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

class OpenCodeChatTransportTest {
  private val diagnostics = RecordingDiagnostics()
  private val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")
  private val store = ChatStore(host, diagnostics, Clock.System)

  private class NoLaunch : ChatProcessLauncher, LiveChatProcessLauncher {
    val commands = mutableListOf<ChatCommand>()
    override fun run(command: ChatCommand): Flow<ChatProcessOutput> { commands += command; return emptyFlow() }
    override fun open(command: ChatCommand, input: ReceiveChannel<String>) = run(command)
  }

  private fun transport(launcher: NoLaunch = NoLaunch(), scope: CoroutineScope) =
    noSessionManager(scope, diagnostics).let { sessions ->
      OpenCodeChatTransport(host, store, diagnostics, launcher, launcher,
        ChatApprovalAuthority(sessions, diagnostics), sessions)
    }

  private fun request(root: String, agent: ChatAgent = ChatAgent("opencode", "open-a", null), firstTurn: Boolean = true) =
    chatRequest(agent = agent, firstTurn = firstTurn)
      .copy(scope = ChatScope.Execution("wf-1", WorkKind.Goal, "SKILL-1", "Title", root))

  // An applied effort the transport cannot forward must not silently reach the provider.
  @Test
  fun `a separate effort setting refuses before probes and settles the turn`() = runTest {
    val launcher = NoLaunch()
    val transport = transport(launcher, backgroundScope)
    val events = transport.run(request("/work/skill-bill", ChatAgent("opencode", "open-a", "high"))).toList()
    val failure = assertIs<ChatEvent.Failed>(events.single())
    assertEquals(ChatFailure.AgentUnsupported, failure.failure)
    assertContains(failure.detail.orEmpty(), "effort")
    assertTrue(launcher.commands.isEmpty())
    assertTrue("chat.opencode_effort_refused" in diagnostics.categories)
    assertEquals(ChatStopSettlement.AlreadyFinished, transport.stopAndAwait("session-1"))
  }

  @Test
  fun `a missing runtime MCP context refuses the turn without launching`() = runTest {
    val launcher = NoLaunch()
    val transport = transport(launcher, backgroundScope)
    val failure = assertIs<ChatEvent.Failed>(
      transport.run(request(createTempDirectory("oc-root").toString())).toList().single())
    assertEquals(ChatFailure.ProcessFailed, failure.failure)
    assertContains(failure.detail.orEmpty(), "runtime MCP context")
    assertTrue(launcher.commands.isEmpty())
  }

  // A handle saved for another profile or checkout belongs to a different conversation, never this turn.
  @Test
  fun `a saved binding from another checkout refuses without replacing history`() = runTest {
    val launcher = NoLaunch()
    val transport = transport(launcher, backgroundScope)
    store.saveChat(ChatHistoryEntry("session-1", "wf-1", ChatAgent("opencode", "open-a", null),
      Clock.System.now(), "question", listOf(ChatHistoryLine(ChatHistoryRole.Operator, "keep"))))
    store.saveOpenCodeSession("session-1", "provider-handle",
      OpenCodeSessionBinding("other-profile", "other-config", "/data/oc", "/elsewhere"))
    val failure = assertIs<ChatEvent.Failed>(
      transport.run(request(createTempDirectory("oc-root").toString(), firstTurn = false)).toList().single())
    assertEquals(ChatFailure.ProcessFailed, failure.failure)
    assertContains(failure.detail.orEmpty(), "no longer matches")
    assertTrue(launcher.commands.isEmpty())
    assertEquals("provider-handle", store.agentSession("session-1"))
    assertTrue("chat.opencode_resume_refused" in diagnostics.categories)
  }

  @Test
  fun `an unreadable saved record refuses and names its original path`() = runTest {
    val launcher = NoLaunch()
    val transport = transport(launcher, backgroundScope)
    val file = File(host.userHome, "$CHAT_STORE_PATH/session-1.json")
    file.parentFile.mkdirs()
    file.writeText("{")
    val failure = assertIs<ChatEvent.Failed>(
      transport.run(request(createTempDirectory("oc-root").toString(), firstTurn = false)).toList().single())
    assertEquals(ChatFailure.ProcessFailed, failure.failure)
    assertContains(failure.detail.orEmpty(), "cannot be read safely")
    assertContains(failure.detail.orEmpty(), file.absolutePath)
    assertTrue(launcher.commands.isEmpty())
  }

  @Test
  fun `unconfirmed image support stays refused without a prompt`() = runTest {
    val launcher = NoLaunch()
    val transport = transport(launcher, backgroundScope)
    val failure = assertIs<ChatEvent.Failed>(
      transport.run(request("/work/skill-bill").copy(
        images = listOf(ChatImage(byteArrayOf(1), "image/png")))).toList().single())
    assertEquals(ChatFailure.ProcessFailed, failure.failure)
    assertContains(failure.detail.orEmpty(), "image support is not confirmed")
    assertTrue(launcher.commands.isEmpty())
  }
}
