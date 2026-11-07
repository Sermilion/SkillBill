package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatReplyStreamTest {
  @Test
  fun `message boundaries flush held text and actions without hiding the following message`() {
    val stream = ChatReplyStream(RecordingDiagnostics())
    assertEquals(listOf(ChatEvent.TextDelta("Before")), stream.accept(ChatEvent.TextDelta("Before`")))
    assertEquals(listOf(ChatEvent.TextDelta("`"), ChatEvent.MessageStarted), stream.accept(ChatEvent.MessageStarted))
    val action = ChatSuggestedAction("Inspect", "Inspect it", ChatPermissionMode.ReadOnly)
    stream.accept(ChatEvent.TextDelta(
      "```skillbill-actions\n" +
        """[{"label":"Inspect","instruction":"Inspect it","mode":"read"}]""" + "\n```",
    ))
    assertEquals(listOf(ChatEvent.SuggestedActions(listOf(action)), ChatEvent.MessageStarted), stream.accept(ChatEvent.MessageStarted))
    assertEquals(listOf(ChatEvent.TextDelta("After")), stream.accept(ChatEvent.TextDelta("After")))
    assertEquals(listOf(ChatEvent.Completed), stream.accept(ChatEvent.Completed))
  }

  @Test
  fun `action metadata stays hidden across every stream split and becomes buttons only after completion`() {
    val answer = "I can inspect CI.\n\n"
    val metadata = """[{"label":"Inspect CI failure","instruction":"Inspect the failed CI job.","mode":"act"}]"""
    val source = answer + "```skillbill-actions\n" + metadata + "\n```"
    val action = ChatSuggestedAction("Inspect CI failure", "Inspect the failed CI job.", ChatPermissionMode.Act)
    for (split in 0..source.length) {
      val diagnostics = RecordingDiagnostics()
      val stream = ChatReplyStream(diagnostics)
      val partial = stream.accept(ChatEvent.TextDelta(source.take(split))) + stream.accept(ChatEvent.TextDelta(source.drop(split)))
      assertEquals(answer, partial.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
      assertTrue(partial.none { it is ChatEvent.SuggestedActions })
      assertEquals(listOf(ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed), stream.accept(ChatEvent.Completed))
      assertTrue(diagnostics.categories.isEmpty())
    }
  }

  @Test
  fun `start monitor keeps string target`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    stream.accept(
      ChatEvent.TextDelta(
        "```skillbill-actions\n" +
          """[{"label":"Run phase:monitor","instruction":"Watch the PR.","mode":"read","start":"monitor","target":"https://github.com/org/repo/pull/9"}]""" +
          "\n```",
      ),
    )
    val actions = stream.accept(ChatEvent.Completed).filterIsInstance<ChatEvent.SuggestedActions>().single().actions
    assertEquals(
      ChatSuggestedAction(
        "Run phase:monitor",
        "Watch the PR.",
        ChatPermissionMode.ReadOnly,
        NewTaskActionKind.Monitor,
        target = "https://github.com/org/repo/pull/9",
      ),
      actions.single(),
    )
    assertTrue(diagnostics.categories.isEmpty())
  }

  @Test
  fun `unknown start drops that action and records diagnostic while sibling is kept`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    stream.accept(
      ChatEvent.TextDelta(
        "```skillbill-actions\n" +
          """[{"label":"Run phase:release","instruction":"Ship.","mode":"act","start":"release"},{"label":"Run phase:review","instruction":"Review branch.","mode":"read","start":"review"}]""" +
          "\n```",
      ),
    )
    val actions = stream.accept(ChatEvent.Completed).filterIsInstance<ChatEvent.SuggestedActions>().single().actions
    assertEquals(
      ChatSuggestedAction("Run phase:review", "Review branch.", ChatPermissionMode.ReadOnly, NewTaskActionKind.Review),
      actions.single(),
    )
    assertEquals(listOf("chat.action_start_unknown"), diagnostics.categories)
  }

  @Test
  fun `start branch is trimmed, a blank one is dropped, and a non-string one drops that action`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    stream.accept(
      ChatEvent.TextDelta(
        "```skillbill-actions\n" +
          """[{"label":"Run phase:review","instruction":"Review it.","mode":"read","start":"review","branch":"  feat/fp-5175 "},""" +
          """{"label":"Run phase:qa","instruction":"QA it.","mode":"read","start":"qa","branch":"  "},""" +
          """{"label":"Run phase:pr","instruction":"Open it.","mode":"act","start":"pr","branch":7}]""" +
          "\n```",
      ),
    )
    val actions = stream.accept(ChatEvent.Completed).filterIsInstance<ChatEvent.SuggestedActions>().single().actions
    assertEquals(
      listOf(
        ChatSuggestedAction("Run phase:review", "Review it.", ChatPermissionMode.ReadOnly, NewTaskActionKind.Review, branch = "feat/fp-5175"),
        ChatSuggestedAction("Run phase:qa", "QA it.", ChatPermissionMode.ReadOnly, NewTaskActionKind.Qa),
      ),
      actions,
    )
    assertEquals(listOf("chat.action_invalid"), diagnostics.categories)
  }

  @Test
  fun `non-string target drops that action`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    stream.accept(
      ChatEvent.TextDelta(
        "```skillbill-actions\n" +
          """[{"label":"Run phase:monitor","instruction":"Watch.","mode":"read","start":"monitor","target":1}]""" +
          "\n```",
      ),
    )
    assertTrue(stream.accept(ChatEvent.Completed).none { it is ChatEvent.SuggestedActions })
    assertEquals(listOf("chat.action_invalid"), diagnostics.categories)
  }

  @Test
  fun `invalid or failed action metadata never creates an executable suggestion`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    assertEquals(
      listOf(ChatEvent.TextDelta("Answer\n")),
      stream.accept(ChatEvent.TextDelta("Answer\n```skillbill-actions\n[{\"label\":\"Go ahead\"}]\n```")),
    )
    val completed = stream.accept(ChatEvent.Completed)
    assertTrue(completed.none { it is ChatEvent.SuggestedActions })
    assertEquals(ChatEvent.Completed, completed.last())
    assertTrue("\"Go ahead\" has no instruction" in completed.filterIsInstance<ChatEvent.TextDelta>().single().text)
    assertEquals(listOf("chat.action_invalid"), diagnostics.categories)

    val malformedDiagnostics = RecordingDiagnostics()
    val malformed = ChatReplyStream(malformedDiagnostics)
    malformed.accept(ChatEvent.TextDelta("Answer\n```skillbill-actions\n[{\"label\":\n```"))
    val malformedEvents = malformed.accept(ChatEvent.Completed)
    assertTrue(malformedEvents.none { it is ChatEvent.SuggestedActions })
    assertTrue("could not be read" in malformedEvents.filterIsInstance<ChatEvent.TextDelta>().single().text)
    assertEquals(listOf("chat.actions_invalid"), malformedDiagnostics.categories)

    val failed = ChatReplyStream(RecordingDiagnostics())
    failed.accept(ChatEvent.TextDelta("Answer\n```skillbill-actions\n[]\n```"))
    val error = ChatEvent.Failed(ChatFailure.ProcessFailed, "failed")
    assertEquals(listOf(error), failed.accept(error))
  }

  @Test
  fun `an instruction over the limit drops only that action and tells the operator why`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    val long = "x".repeat(2031)
    stream.accept(
      ChatEvent.TextDelta(
        "Ready.\n```skillbill-actions\n" +
          """[{"label":"Run phase:goal","instruction":"$long","mode":"act","start":"goal","title":"Redesign validation page"},""" +
          """{"label":"Use SKILL-12","instruction":"SKILL-12","mode":"read"}]""" +
          "\n```",
      ),
    )
    val events = stream.accept(ChatEvent.Completed)
    val notice = events.filterIsInstance<ChatEvent.TextDelta>().single().text
    assertTrue("\"Run phase:goal\" has an instruction of 2031 characters, over the 2000 limit" in notice)
    assertEquals(
      listOf(ChatSuggestedAction("Use SKILL-12", "SKILL-12", ChatPermissionMode.ReadOnly)),
      events.filterIsInstance<ChatEvent.SuggestedActions>().single().actions,
    )
    assertEquals(ChatEvent.Completed, events.last())
    assertEquals(listOf("chat.action_invalid"), diagnostics.categories)
  }

  @Test
  fun `a valid title decodes and an invalid title keeps the action`() {
    val validDiagnostics = RecordingDiagnostics()
    val valid = ChatReplyStream(validDiagnostics)
    valid.accept(
      ChatEvent.TextDelta(
        "Answer\n```skillbill-actions\n" +
          "[{\"label\":\"Start a goal run\",\"instruction\":\"Implement the change.\",\"mode\":\"act\",\"start\":\"goal\",\"title\":\"Add dark mode setting\"}]\n```",
      ),
    )
    assertEquals(
      listOf(
        ChatEvent.SuggestedActions(
          listOf(
            ChatSuggestedAction(
              "Start a goal run",
              "Implement the change.",
              ChatPermissionMode.Act,
              NewTaskActionKind.Goal,
              "Add dark mode setting",
            ),
          ),
        ),
        ChatEvent.Completed,
      ),
      valid.accept(ChatEvent.Completed),
    )
    assertTrue(validDiagnostics.categories.isEmpty())

    val invalidDiagnostics = RecordingDiagnostics()
    val invalid = ChatReplyStream(invalidDiagnostics)
    invalid.accept(
      ChatEvent.TextDelta(
        "Answer\n```skillbill-actions\n" +
          "[{\"label\":\"Start a goal run\",\"instruction\":\"Implement the change.\",\"mode\":\"act\",\"start\":\"goal\",\"title\":\"!!!\"}]\n```",
      ),
    )
    assertEquals(
      listOf(
        ChatEvent.SuggestedActions(
          listOf(
            ChatSuggestedAction(
              "Start a goal run",
              "Implement the change.",
              ChatPermissionMode.Act,
              NewTaskActionKind.Goal,
            ),
          ),
        ),
        ChatEvent.Completed,
      ),
      invalid.accept(ChatEvent.Completed),
    )
    assertEquals(listOf("chat.action_title_invalid"), invalidDiagnostics.categories)
    assertTrue("chat.actions_invalid" !in invalidDiagnostics.categories)
  }
}
