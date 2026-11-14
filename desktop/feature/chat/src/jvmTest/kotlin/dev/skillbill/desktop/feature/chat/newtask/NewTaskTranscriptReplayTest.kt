package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewTaskTranscriptReplayTest {
  private fun agent(text: String, streaming: Boolean = false) =
    ChatMessage.Agent(text, streaming = streaming, tools = emptyList(), actions = emptyList())

  private fun conversation(preamble: String?) = checkNotNull(preamble).substringAfter("conversation:\n\n")

  @Test
  fun `keeps finished conversation, names changes and drops chrome`() {
    val image = ChatImage(byteArrayOf(1), "image/png")
    val replay = NewTaskReplay(
      "claude",
      "/old",
      null,
      listOf(
        ChatMessage.StateLine("Running"),
        ChatMessage.Operator("hi", null),
        ChatMessage.Notice("Switched"),
        ChatMessage.Error(ChatFailure.BinaryMissing, "missing"),
        agent("hello"),
        ChatMessage.Operator("", images = listOf(image, image)),
        agent("partial", streaming = true),
      ),
    )
    assertEquals(
      "Earlier in this chat you were talking with claude. Here is that conversation:\n" +
        "The effective working directory changed from /old to /new.\n" +
        "The selected repository changed from /old to /new.\n" +
        "The base branch changed from the default branch to dev.\n\n" +
        "Operator: hi\n\nAgent: hello\n\nOperator: [Image #1] [Image #2]\n\nContinue from here.",
      newTaskReplayPreamble(replay, "/new", "dev"),
    )
  }

  @Test
  fun `names no change when the root and branch match, counting a blank branch as the default`() {
    val replay = NewTaskReplay("claude", "/r", null, listOf(ChatMessage.Operator("hi", null)))
    assertEquals(
      "Earlier in this chat you were talking with claude. Here is that conversation:\n\nOperator: hi\n\nContinue from here.",
      newTaskReplayPreamble(replay, "/r", ""),
    )
  }

  @Test
  fun `drops oldest messages over budget and truncates an oversized newest one`() {
    val big = "x".repeat(NEW_TASK_REPLAY_BUDGET)
    val dropped = newTaskReplayPreamble(NewTaskReplay("a", "/r", "b", listOf(ChatMessage.Operator(big, null), agent("ok"))), "/r", "b")
    assertEquals("Older messages were left out.\n\nAgent: ok\n\nContinue from here.", conversation(dropped))
    val filler = "z".repeat(NEW_TASK_REPLAY_BUDGET - 30)
    val oldestDropped = newTaskReplayPreamble(
      NewTaskReplay("a", "/r", "b", listOf(ChatMessage.Operator("first", null), ChatMessage.Operator(filler, null), agent("ok"))),
      "/r",
      "b",
    )
    assertEquals("Older messages were left out.\n\nOperator: $filler\n\nAgent: ok\n\nContinue from here.", conversation(oldestDropped))
    val truncated = newTaskReplayPreamble(NewTaskReplay("a", "/r", "b", listOf(agent("y$big"))), "/r", "b")
    val kept = "Agent: " + "x".repeat(NEW_TASK_REPLAY_BUDGET - "Agent: ".length)
    assertEquals("Older messages were left out.\n\n$kept\n\nContinue from here.", conversation(truncated))
  }

  @Test
  fun `returns null when nothing survives`() {
    assertNull(newTaskReplayPreamble(NewTaskReplay("a", "/r", null, listOf(ChatMessage.Notice("n"), agent(" "))), "/r", null))
  }
}
