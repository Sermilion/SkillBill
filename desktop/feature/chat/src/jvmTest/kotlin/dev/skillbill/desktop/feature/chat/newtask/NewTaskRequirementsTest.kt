package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
  fun `title is the newest finished goal or plan title`() {
    fun agent(kind: NewTaskActionKind, title: String, streaming: Boolean = false) = ChatMessage.Agent(
      "",
      streaming = streaming,
      tools = emptyList(),
      actions = listOf(ChatSuggestedAction("Start", "Go", ChatPermissionMode.Act, kind, title)),
    )

    assertEquals(
      "Newer plan",
      newTaskTitle(
        listOf(
          agent(NewTaskActionKind.Goal, "Older goal"),
          agent(NewTaskActionKind.Plan, "Newer plan"),
          agent(NewTaskActionKind.Review, "Review title"),
          agent(NewTaskActionKind.Goal, "Still streaming", streaming = true),
        ),
      ),
    )
    assertNull(newTaskTitle(listOf(agent(NewTaskActionKind.Review, "Review title"))))
  }
}
