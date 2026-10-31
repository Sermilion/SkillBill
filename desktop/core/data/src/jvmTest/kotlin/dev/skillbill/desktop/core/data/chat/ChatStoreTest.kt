package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class ChatStoreTest {
  private val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")

  private fun entry(session: String, at: Long, execution: String = "wf-1", operator: Boolean = true) = ChatHistoryEntry(
    session, execution, ChatAgent("codex", "gpt-5", "high", "/home/operator/.codex-api"), Instant.fromEpochMilliseconds(at), "why blocked?",
    listOfNotNull(ChatHistoryLine(ChatHistoryRole.Operator, "why blocked?").takeIf { operator }, ChatHistoryLine(ChatHistoryRole.Agent, "CI failed.")),
  )

  // A restart reads the files back, so a write the reader cannot decode, or a resume handle that clobbers the lines, loses the chat.
  @Test
  fun `a fresh store lists one execution's operator chats newest first with their resume handle kept apart`() {
    val writer = ChatStore(host, RecordingDiagnostics(), Clock.System)
    writer.saveChat(entry("old", 1))
    writer.saveAgentSession("new", "thread-9")
    writer.saveChat(entry("new", 2))
    writer.saveChat(entry("opening-only", 3, operator = false))
    writer.saveChat(entry("other", 4, execution = "wf-2"))

    val reader = ChatStore(host, RecordingDiagnostics(), Clock.System)

    assertEquals(listOf(entry("new", 2), entry("old", 1)), reader.savedChats("wf-1"))
    assertEquals("thread-9", reader.agentSession("new"))
  }

  // Every chat open saves a file, so without a cap the directory grows for the life of the machine.
  @Test
  fun `saving keeps only the newest twenty chats of an execution`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)

    (1L..22L).forEach { store.saveChat(entry("s$it", it)) }

    assertEquals((22L downTo 3L).map { "s$it" }, store.savedChats("wf-1").map { it.sessionId })
  }
}
