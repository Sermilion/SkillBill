package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatReplyStreamTest {
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
  fun `a valid title decodes and an invalid one keeps the action without it`() {
    val decode = { title: String, diagnostics: RecordingDiagnostics ->
      val stream = ChatReplyStream(diagnostics)
      stream.accept(ChatEvent.TextDelta("```skillbill-actions\n[{\"label\":\"Start\",\"instruction\":\"Go\",\"mode\":\"act\",\"title\":\"$title\"}]\n```"))
      stream.accept(ChatEvent.Completed).first()
    }
    val untitled = ChatSuggestedAction("Start", "Go", ChatPermissionMode.Act)

    val valid = RecordingDiagnostics()
    assertEquals(ChatEvent.SuggestedActions(listOf(untitled.copy(title = "Add dark mode"))), decode(" Add dark mode ", valid))
    assertTrue(valid.categories.isEmpty())

    val invalid = RecordingDiagnostics()
    assertEquals(ChatEvent.SuggestedActions(listOf(untitled)), decode("!!!", invalid))
    assertEquals(listOf("chat.action_title_invalid"), invalid.categories)
  }

  @Test
  fun `invalid or failed action metadata never creates an executable suggestion`() {
    val diagnostics = RecordingDiagnostics()
    val stream = ChatReplyStream(diagnostics)
    assertEquals(
      listOf(ChatEvent.TextDelta("Answer\n")),
      stream.accept(ChatEvent.TextDelta("Answer\n```skillbill-actions\n[{\"label\":\"Go ahead\"}]\n```")),
    )
    assertEquals(listOf(ChatEvent.Completed), stream.accept(ChatEvent.Completed))
    assertEquals(listOf("chat.actions_invalid"), diagnostics.categories)

    val failed = ChatReplyStream(RecordingDiagnostics())
    failed.accept(ChatEvent.TextDelta("Answer\n```skillbill-actions\n[]\n```"))
    val error = ChatEvent.Failed(ChatFailure.ProcessFailed, "failed")
    assertEquals(listOf(error), failed.accept(error))
  }
}
