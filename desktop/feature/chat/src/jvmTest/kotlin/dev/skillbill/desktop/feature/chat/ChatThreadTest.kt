package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class ChatThreadTest {
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
}
