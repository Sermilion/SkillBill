package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatStoreRead
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.CursorAcpMcpDependency
import skillbill.infrastructure.launcher.process.cursor.CursorAcpSession
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

class AgentChatTransportsTest {
  private val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")
  private val store = ChatStore(host, RecordingDiagnostics(), Clock.System)

  private class NoLaunch : ChatProcessLauncher, LiveChatProcessLauncher {
    val commands = mutableListOf<ChatCommand>()
    override fun run(command: ChatCommand): Flow<ChatProcessOutput> { commands += command; return emptyFlow() }
    override fun open(command: ChatCommand, input: kotlinx.coroutines.channels.ReceiveChannel<String>) = run(command)
  }

  @Test
  fun `ACP and app-server agents without an operator channel launch nothing and retain the saved conversation`() = runTest {
    val launcher = NoLaunch()
    val request = chatRequest(agent = ChatAgent("codex", null, null), firstTurn = false)
    val history = listOf(
      ChatHistoryLine(ChatHistoryRole.Operator, "earlier question"),
      ChatHistoryLine(ChatHistoryRole.Agent, "earlier answer"),
    )
    assertTrue(store.saveChat(ChatHistoryEntry(
      request.sessionId, "execution", request.agent, Clock.System.now(), "earlier question", history,
    )))
    store.saveAgentSession(request.sessionId, "saved-provider-thread")
    val sessions = noSessionManager(backgroundScope, RecordingDiagnostics())
    val transports = listOf(
      CodexChatTransport(host, launcher, store, RecordingDiagnostics()),
      CursorChatTransport(host, store, RecordingDiagnostics(), liveLauncher = launcher),
      DevinChatTransport(host, store, RecordingDiagnostics(), launcher, null, sessions),
      OpenCodeChatTransport(host, store, RecordingDiagnostics(), launcher, launcher, null, sessions),
    )
    for (transport in transports) {
      val failure = assertIs<ChatEvent.Failed>(transport.run(request.copy(
        agent = ChatAgent(transport.agentId, null, null),
        cursorMcpDependency = CursorMcpDependency.ProjectUserOnly,
      )).toList().single())
      assertEquals(ChatFailure.ApprovalUnsupported, failure.failure)
      assertContains(failure.detail.orEmpty(), "operator approval channel")
      assertTrue(launcher.commands.isEmpty())
      assertEquals("saved-provider-thread", store.agentSession(request.sessionId))
      assertEquals(history, (store.replayHistoryRead(request.sessionId) as ChatStoreRead.Value).value)
    }
  }

  @Test
  fun `image acquisition preserves original bytes separate paths and session cleanup`() {
    val session = "images-${UUID.randomUUID()}"
    val images = listOf(ChatImage(byteArrayOf(1, 2), "image/png"), ChatImage(byteArrayOf(3), "image/jpeg"))
    try {
      val paths = writeChatImages(session, images)
      assertEquals(2, paths.distinct().size)
      paths.forEachIndexed { index, path -> assertContentEquals(images[index].bytes, Files.readAllBytes(java.nio.file.Path.of(path))) }
      assertTrue(paths[0].endsWith(".png") && paths[1].endsWith(".jpg"))
    } finally { deleteChatImages(session) }
    assertFalse(Files.exists(chatImageDirectory(session)))
  }

  @Test
  fun `session cleanup removes a planted symlink without following it`() {
    val session = "images-${UUID.randomUUID()}"
    val outside = createTempDirectory("chat-images-outside")
    val kept = Files.write(outside.resolve("kept.txt"), byteArrayOf(1))
    Files.createDirectories(chatImageRoot())
    val link = Files.createSymbolicLink(chatImageDirectory(session), outside)
    try {
      deleteChatImages(session)
      assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.exists(kept))
    } finally { Files.deleteIfExists(link); outside.toFile().deleteRecursively() }
  }
}
