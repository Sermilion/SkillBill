package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `asksToRunGoal matches an explicit start and ignores a feature description or issue question`() {
    assertTrue(asksToRunGoal("Start a goal run"))
    assertTrue(asksToRunGoal("let's run this as a goal"))
    assertTrue(asksToRunGoal("plan the export"))
    assertTrue(asksToRunGoal("implement this"))
    assertFalse(asksToRunGoal("Add CSV export to the history screen"))
    assertFalse(asksToRunGoal("Add a release button"))
    assertFalse(asksToRunGoal("what's in this Linear issue?"))
    assertFalse(asksToRunGoal("the goal is to add export"))
  }

  @Test
  fun `the goal card stays hidden for a question about an issue`() {
    val transcript = listOf(
      ChatMessage.Operator("https://linear.app/team/issue/SKILL-521/hide-the-goal-run-card", null),
      ChatMessage.Agent(
        "I inferred that from the issue title in the URL. I hadn't opened Linear, so I couldn't say what the report or acceptance criteria actually contain.",
        streaming = false,
        tools = emptyList(),
      ),
    )
    assertFalse(showGoalOfferCard(true, transcript, NewTaskOffer()))
  }

  @Test
  fun `the goal card appears when the operator asks to run a goal or the latest reply offers one`() {
    val idle = NewTaskOffer()
    val ask = listOf(ChatMessage.Operator("start a goal run", null))
    assertTrue(showGoalOfferCard(true, ask, idle))
    assertFalse(showGoalOfferCard(false, ask, idle))

    val offered = listOf(
      ChatMessage.Operator("Add CSV export to the history screen", null),
      reply(NewTaskActionKind.Goal),
    )
    assertTrue(showGoalOfferCard(true, offered, idle))
    assertTrue(showGoalOfferCard(true, listOf(ChatMessage.Operator("Add CSV export", null), reply(NewTaskActionKind.Plan)), idle))
  }

  @Test
  fun `a goal-run ask stays on the card through clarification until a standalone route takes over`() {
    val idle = NewTaskOffer()
    val clarifying = listOf(
      ChatMessage.Operator("start a goal run", null),
      ChatMessage.Agent("What does done mean?", streaming = false, tools = emptyList()),
      ChatMessage.Operator("CSV download from the history screen.", null),
    )
    assertTrue(showGoalOfferCard(true, clarifying, idle))

    val switched = clarifying + ChatMessage.Operator("review this branch", null)
    assertFalse(showGoalOfferCard(true, switched, idle))
    assertFalse(showGoalOfferCard(true, listOf(ChatMessage.Operator("start a goal run", null), reply(NewTaskActionKind.Review)), idle))
  }

  @Test
  fun `a pending prefix, problem or started confirmation keeps the card when the transcript would hide it`() {
    val question = listOf(ChatMessage.Operator("what's in this issue?", null), ChatMessage.Agent("I inferred the title.", streaming = false, tools = emptyList()))
    assertTrue(showGoalOfferCard(true, question, NewTaskOffer(prefixKind = NewTaskKind.GoalRun)))
    assertTrue(showGoalOfferCard(true, question, NewTaskOffer(problem = "Choose a prefix.")))
    assertTrue(showGoalOfferCard(true, question, NewTaskOffer(started = "Started goal run SKILL-1")))
    assertFalse(showGoalOfferCard(true, question, NewTaskOffer()))
  }

  @Test
  fun `a latest standalone reply or standalone operator request hides the goal card`() {
    val idle = NewTaskOffer()
    assertFalse(showGoalOfferCard(true, listOf(reply(NewTaskActionKind.Review)), idle))
    assertFalse(showGoalOfferCard(true, listOf(ChatMessage.Operator("publish a minor release", null)), idle))
    assertFalse(showGoalOfferCard(true, listOf(ChatMessage.Operator("Review my current branch", null)), idle))
    assertFalse(offersGoalOrPlanStart(listOf(reply(NewTaskActionKind.Review))))
    assertTrue(offersGoalOrPlanStart(listOf(reply(NewTaskActionKind.Goal))))
  }

  private fun reply(start: NewTaskActionKind?) = ChatMessage.Agent(
    "ok", false, emptyList(), listOf(ChatSuggestedAction("Go", "Go", ChatPermissionMode.Act, start = start)),
  )
}
