package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.VerifyQaChoice
import dev.skillbill.desktop.core.domain.model.VerifyReviewModeChoice
import dev.skillbill.desktop.core.domain.model.VerifyStartChoices
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ChatReplyStreamTest {
  private val repositoryPath = Path.of("repository-candidate").toAbsolutePath().toString()

  @Test
  fun `repository candidate stays hidden across chunks and message boundaries until completed`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    val prefix = "I found it.\n\n"
    val block = "```skillbill-repository\n{\"contract_version\":\"1\",\"path\":${JsonPrimitive(repositoryPath)}}\n```"
    val first = prefix + block.take(13)
    assertEquals(listOf(ChatEvent.TextDelta(prefix)), stream.accept(ChatEvent.TextDelta(first)))
    assertEquals(listOf(ChatEvent.MessageStarted), stream.accept(ChatEvent.MessageStarted))
    assertTrue(stream.accept(ChatEvent.TextDelta(block.drop(13))).none { it is ChatEvent.RepositoryCandidate })
    assertEquals(
      listOf(ChatEvent.RepositoryCandidate(repositoryPath), ChatEvent.Completed),
      stream.accept(ChatEvent.Completed),
    )
    assertTrue(diagnostics.categories.isEmpty())
  }

  @Test
  fun `invalid repository metadata produces a notice and never a candidate`() {
    val malformed = listOf(
      "```skillbill-repository\n{\"contract_version\":\"2\",\"path\":${JsonPrimitive(repositoryPath)}}\n```",
      "```skillbill-repository\n{\"contract_version\":\"1\",\"path\":\"relative\"}\n```",
      "```skillbill-repository\n{\"contract_version\":\"1\",\"path\":${JsonPrimitive(repositoryPath)},\"extra\":true}\n```",
      "```skillbill-repository\n{\"contract_version\":\"1\",\"path\":${JsonPrimitive(repositoryPath)}}\n``` trailing",
      "```skillbill-repository\n{\"contract_version\":\"1\",\"path\":${JsonPrimitive(repositoryPath)}}\n```\n```skillbill-repository\n{\"contract_version\":\"1\",\"path\":${JsonPrimitive(repositoryPath + "-other")}}\n```",
      "```skillbill-repository\n{\"contract_version\":\"1\",\"path\":${JsonPrimitive(repositoryPath)}}\n```\n```skillbill-actions\n[]\n```",
      "```skillbill-repository\n${"x".repeat(8_200)}\n```",
    )
    malformed.forEach { block ->
      val diagnostics = RecordingDiagnostics()
      val stream = ChatReplyStream(diagnostics)
      stream.accept(ChatEvent.TextDelta(block))
      val terminal = stream.accept(ChatEvent.Completed)
      assertFalse(terminal.any { it is ChatEvent.RepositoryCandidate })
      assertTrue(terminal.filterIsInstance<ChatEvent.TextDelta>().any { "could not use the repository selection block" in it.text })
      assertTrue(diagnostics.categories.isNotEmpty())
    }
  }

  @Test
  fun `failed and unprompted repository replies cannot select`() {
    val failed = ChatReplyStream(RecordingDiagnostics())
    val block = "```skillbill-repository\n{\"contract_version\":\"1\",\"path\":${JsonPrimitive(repositoryPath)}}\n```"
    failed.accept(ChatEvent.TextDelta(block))
    assertFalse(failed.accept(ChatEvent.Failed(ChatFailure.ProcessFailed, null)).any { it is ChatEvent.RepositoryCandidate })

    val unprompted = ChatReplyStream(RecordingDiagnostics(), allowRepositoryCandidates = false)
    unprompted.accept(ChatEvent.TextDelta(block))
    assertFalse(unprompted.accept(ChatEvent.Completed).any { it is ChatEvent.RepositoryCandidate })
  }

  @Test
  fun `message boundaries retain actions until the turn ends without hiding the following message`() {
    val stream = ChatReplyStream(RecordingDiagnostics())
    assertEquals(listOf(ChatEvent.TextDelta("Before")), stream.accept(ChatEvent.TextDelta("Before`")))
    assertEquals(listOf(ChatEvent.MessageStarted), stream.accept(ChatEvent.MessageStarted))
    val action = ChatSuggestedAction("Inspect", "Inspect it", ChatPermissionMode.ReadOnly)
    assertEquals(listOf(ChatEvent.TextDelta("`")), stream.accept(ChatEvent.TextDelta(
      "```skillbill-actions\n" +
        """[{"label":"Inspect","instruction":"Inspect it","mode":"read"}]""" + "\n```",
    )))
    assertEquals(listOf(ChatEvent.MessageStarted), stream.accept(ChatEvent.MessageStarted))
    assertEquals(listOf(ChatEvent.TextDelta("After")), stream.accept(ChatEvent.TextDelta("After")))
    assertEquals(listOf(ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed), stream.accept(ChatEvent.Completed))
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
  fun `qa and review_mode reach a verify start and drop any other action that sets them`() {
    val stream = ChatReplyStream(RecordingDiagnostics())
    stream.accept(
      ChatEvent.TextDelta(
        "```skillbill-actions\n" +
          """[{"label":"Run operation:verify","instruction":"Verify it.","mode":"act","start":"verify","target":"WE-1","qa":"on","review_mode":"delegated"},""" +
          """{"label":"Run phase:review","instruction":"Review it.","mode":"read","start":"review","qa":"on"},""" +
          """{"label":"Verify again","instruction":"Verify it.","mode":"act","start":"verify","target":"WE-1","qa":"yes"}]""" +
          "\n```",
      ),
    )
    val actions = stream.accept(ChatEvent.Completed).filterIsInstance<ChatEvent.SuggestedActions>().single().actions
    assertEquals(
      listOf(
        ChatSuggestedAction(
          "Run operation:verify",
          "Verify it.",
          ChatPermissionMode.Act,
          NewTaskActionKind.Verify,
          target = "WE-1",
          verify = VerifyStartChoices(VerifyQaChoice.On, VerifyReviewModeChoice.Delegated),
        ),
      ),
      actions,
    )
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
  fun `long instructions survive streamed action decoding without truncation`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    val long = "x".repeat(20_031)
    stream.accept(
      ChatEvent.TextDelta(
        "Ready.\n```skillbill-actions\n" +
          """[{"label":"Run phase:goal","instruction":"$long","mode":"act","start":"goal","title":"Redesign validation page"},""" +
          """{"label":"Use SKILL-12","instruction":"SKILL-12","mode":"read"}]""" +
          "\n```",
      ),
    )
    val events = stream.accept(ChatEvent.Completed)
    assertTrue(events.none { it is ChatEvent.TextDelta })
    assertEquals(
      listOf(
        ChatSuggestedAction("Run phase:goal", long, ChatPermissionMode.Act, NewTaskActionKind.Goal, "Redesign validation page"),
        ChatSuggestedAction("Use SKILL-12", "SKILL-12", ChatPermissionMode.ReadOnly),
      ),
      events.filterIsInstance<ChatEvent.SuggestedActions>().single().actions,
    )
    assertEquals(ChatEvent.Completed, events.last())
    assertTrue(diagnostics.categories.isEmpty())
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
