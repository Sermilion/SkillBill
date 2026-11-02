package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class NewTaskRequirementsTest {
  @Test
  fun `keeps operator messages verbatim and labels agent context before later replies`() {
    val messages = listOf(
      ChatMessage.Operator("  Add export support.  "),
      ChatMessage.Agent("Which formats?", streaming = false, tools = emptyList()),
      ChatMessage.Operator("CSV and JSON."),
      ChatMessage.Agent("Any existing API?", streaming = false, tools = emptyList()),
    )

    assertEquals(
      "  Add export support.  \n\nChat context. Agent lines are questions, not requirements.\n" +
        "Agent: Which formats?\n\nOperator: CSV and JSON.",
      newTaskRequirements(messages),
    )
  }

  @Test
  fun `newTaskTitle returns the newer goal or plan title and skips streaming and review actions`() {
    val older = ChatSuggestedAction("Start a goal run", "Implement", ChatPermissionMode.Act, NewTaskActionKind.Goal, "Old title")
    val review = ChatSuggestedAction("Review", "Review", ChatPermissionMode.ReadOnly, NewTaskActionKind.Review, "Review title")
    val streaming = ChatSuggestedAction("Start a plan", "Plan", ChatPermissionMode.Act, NewTaskActionKind.Plan, "Streaming title")
    val newer = ChatSuggestedAction("Start a plan", "Plan", ChatPermissionMode.Act, NewTaskActionKind.Plan, "Add dark mode setting")
    val messages = listOf(
      ChatMessage.Operator("Add export"),
      ChatMessage.Agent("older", streaming = false, tools = emptyList(), actions = listOf(older)),
      ChatMessage.Agent("streaming", streaming = true, tools = emptyList(), actions = listOf(streaming)),
      ChatMessage.Agent("review", streaming = false, tools = emptyList(), actions = listOf(review)),
      ChatMessage.Agent("newer", streaming = false, tools = emptyList(), actions = listOf(newer)),
    )

    assertEquals("Add dark mode setting", newTaskTitle(messages))
  }
}
