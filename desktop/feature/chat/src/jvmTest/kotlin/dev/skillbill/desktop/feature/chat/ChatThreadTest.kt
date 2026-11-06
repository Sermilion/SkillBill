package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
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
  fun `unsupported Junie reason and historical prose restore without a runnable approval`() {
    val thread = ChatThread(Clock.System)
    thread.append(ChatMessage.Agent("Earlier Junie prose", false, emptyList()))
    val reason = "Junie ACP permission-request coverage remains unverified. " + "Full explanation. ".repeat(1000)
    thread.show(ChatAgent("junie", null, null), ChatEvent.Failed(ChatFailure.ApprovalUnsupported, reason))
    val lines = thread.historyLines()
    assertEquals("Earlier Junie prose", lines[0].text)
    assertEquals(ChatHistoryRole.Error, lines[1].role)
    assertEquals(reason, lines[1].text)
    assertEquals(ChatMessage.Error(ChatFailure.ApprovalUnsupported, reason), restored(lines[1]))
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
