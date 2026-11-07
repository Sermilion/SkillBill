package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

  @Test
  fun `operator identity accepts dedicated keys and URLs but ignores mentions and suggested actions`() {
    val suggested = ChatSuggestedAction("Start", "Start this", ChatPermissionMode.Act, NewTaskActionKind.Goal)

    assertEquals("SKILL-479", newTaskOperatorIdentity(listOf(ChatMessage.Operator("SKILL-479"))))
    assertEquals("SKILL-479", newTaskOperatorIdentity(listOf(ChatMessage.Operator("SKILL-479 Add export"))))
    val url = "https://linear.app/team/issue/SKILL-479/export?view=full"
    assertEquals(url, newTaskOperatorIdentity(listOf(ChatMessage.Operator(url))))
    assertNull(newTaskOperatorIdentity(listOf(ChatMessage.Operator("Please fix SKILL-476 while adding export"))))
    assertNull(newTaskOperatorIdentity(listOf(ChatMessage.Agent("SKILL-477", false, emptyList()))))
    assertNull(newTaskOperatorIdentity(listOf(ChatMessage.Operator("SKILL-478", suggested))))
    assertNull(newTaskCompleteKeyOrUrl("NEWS"))
    assertEquals("news-12", newTaskCompleteKeyOrUrl("news-12"))
  }

  @Test
  fun `a clicked key chip counts only when it names the bare key on its label`() {
    fun chip(label: String, instruction: String, start: NewTaskActionKind? = null) =
      ChatMessage.Operator(instruction, ChatSuggestedAction(label, instruction, ChatPermissionMode.ReadOnly, start))

    val transcript = listOf(ChatMessage.Operator("Fix the start flow"), chip("Use SKILL-509 for this work", "SKILL-509"))
    assertEquals("SKILL-509", newTaskOperatorIdentity(transcript))
    assertNull(newTaskOperatorIdentity(listOf(chip("Use this key", "SKILL-509"))))
    assertNull(newTaskOperatorIdentity(listOf(chip("Use SKILL-509 for this work", "Use SKILL-509 for this work"))))
    assertNull(newTaskOperatorIdentity(listOf(chip("Start SKILL-509", "SKILL-509", NewTaskActionKind.Goal))))
    assertNull(newTaskOperatorIdentity(listOf(chip("Use SKILL-508", "SKILL-509"))))
  }

  @Test
  fun `reuse requires a typed complete key and reuse wording`() {
    val reuse = listOf(ChatMessage.Operator("Please reuse SKILL-470"))

    assertTrue(newTaskReuseRequested(reuse, "SKILL-470", null, null, null, null))
    assertFalse(newTaskReuseRequested(listOf(ChatMessage.Operator("reuse the old item")), "SKILL-470", null, null, null, null))
    assertFalse(newTaskReuseRequested(reuse, "https://linear.app/issue/SKILL-470", null, null, null, null))
    assertFalse(newTaskReuseRequested(listOf(ChatMessage.Operator("SKILL-470")), "SKILL-470", null, null, null, null))
  }
}
