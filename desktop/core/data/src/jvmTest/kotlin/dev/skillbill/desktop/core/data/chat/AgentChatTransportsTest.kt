package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
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
    store.saveAgentSession(request.sessionId, "saved-provider-thread")
    val transports = listOf(
      CodexChatTransport(host, launcher, store, RecordingDiagnostics()),
      CursorChatTransport(host, store, RecordingDiagnostics(), liveLauncher = launcher),
      JunieChatTransport(host, store, RecordingDiagnostics(), liveLauncher = launcher),
      DevinChatTransport(host, store, RecordingDiagnostics(), liveLauncher = launcher),
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
    }
  }

  @Test
  fun `devin turns launch devin acp and replay earlier exchanges with the new message`() {
    assertEquals(listOf("devin", "acp"), acpCommand(AcpProvider.Devin, null))
    assertEquals(listOf("junie", "--acp"), acpCommand(AcpProvider.Junie, null))
    assertEquals(AcpProvider.Devin.id, DevinChatTransport(host, store, RecordingDiagnostics(), liveLauncher = NoLaunch()).agentId)
    val prompt = replayPrompt("", listOf(ChatExchange("first question", "first answer")), "next question")
    assertTrue(prompt.indexOf("first answer") in 0 until prompt.indexOf("next question"))
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
