package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class ChatThreadTest {
  @Test
  fun `stream chunks join within a message and separate messages survive history restoration`() {
    val thread = ChatThread(Clock.System)
    val call = ChatToolCall("t1", "shell", "git status", null)
    listOf(
      ChatEvent.MessageStarted, ChatEvent.TextDelta("Check"), ChatEvent.TextDelta("ing."), ChatEvent.ToolUse(call),
      ChatEvent.MessageStarted, ChatEvent.TextDelta("Done."), ChatEvent.ToolResult("t1", "clean", false), ChatEvent.Completed,
    ).forEach { thread.show(null, it) }
    val messages = thread.transcript.value.filterIsInstance<ChatMessage.Agent>()
    assertEquals(listOf("Checking.", "Done."), messages.map { it.text })
    assertEquals(listOf(false, false), messages.map { it.streaming })
    assertEquals("clean", messages.first().tools.single().result?.output)
    assertEquals(listOf("Checking.", "Done."), thread.historyLines().map(::restored).filterIsInstance<ChatMessage.Agent>().map { it.text })
  }

  @Test
  fun `tool-only messages share one tool group until a message with text closes the entry`() {
    val thread = ChatThread(Clock.System)
    val calls = (1..3).map { ChatToolCall("t$it", "Bash", "{}", null) }
    calls.forEach { call ->
      thread.show(null, ChatEvent.MessageStarted)
      thread.show(null, ChatEvent.ToolUse(call))
      thread.show(null, ChatEvent.ToolResult(call.id, "ok", false))
    }
    listOf(ChatEvent.MessageStarted, ChatEvent.TextDelta("Done."), ChatEvent.Completed).forEach { thread.show(null, it) }
    val entry = thread.transcript.value.filterIsInstance<ChatMessage.Agent>().single()
    assertEquals(listOf("t1", "t2", "t3"), entry.tools.map { it.call.id })
    assertEquals("Done.", entry.text)
  }

  @Test
  fun `history keeps action-only replies and selected operator actions for restoration`() {
    val suggested = listOf(ChatSuggestedAction("Review", "Review this", ChatPermissionMode.ReadOnly, NewTaskActionKind.Review))
    val selected = ChatSuggestedAction("Inspect", "Inspect this", ChatPermissionMode.Act)
    val thread = ChatThread(Clock.System)
    thread.append(ChatMessage.Operator(selected.instruction, selected))
    thread.append(ChatMessage.Agent("", streaming = false, tools = emptyList(), actions = suggested))

    val lines = thread.historyLines()

    assertEquals(
      listOf(
        ChatHistoryLine(ChatHistoryRole.Operator, selected.instruction, selectedAction = selected),
        ChatHistoryLine(ChatHistoryRole.Agent, "", actions = suggested),
      ),
      lines,
    )
    assertEquals(selected, (restored(lines[0]) as ChatMessage.Operator).action)
    assertEquals(suggested, (restored(lines[1]) as ChatMessage.Agent).actions)
  }

  @Test
  fun `images stay in the session and a restored operator message has none`() {
    val thread = ChatThread(Clock.System)
    thread.append(ChatMessage.Operator("look", images = listOf(ChatImage(byteArrayOf(1), "image/png"))))

    assertEquals(listOf(ChatMessage.Operator("look", null)), thread.historyLines().map(::restored))
  }

  @Test
  fun `an image-only message is saved as its image labels and no image data`() {
    val thread = ChatThread(Clock.System)
    val image = ChatImage(byteArrayOf(1), "image/png")
    thread.append(ChatMessage.Operator("", images = listOf(image, image)))

    assertEquals(listOf(ChatMessage.Operator("[Image #1] [Image #2]", null)), thread.historyLines().map(::restored))
  }

  @Test
  fun `a start confirmation notice restores as a confirmation`() {
    val text = "Started goal run APP-1. $START_AGAIN_SUFFIX"
    val notice = restored(ChatHistoryLine(ChatHistoryRole.Notice, text)) as ChatMessage.Notice
    assertEquals(text, notice.text)
    assertEquals(true, notice.confirmation)
    assertEquals(false, (restored(ChatHistoryLine(ChatHistoryRole.Notice, "Stopped")) as ChatMessage.Notice).confirmation)
  }
}
