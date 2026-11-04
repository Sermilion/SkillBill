package skillbill.engine.operation.prreviewfix

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.diffcomment.model.DiffCommentOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalReviewFixOutcomeParsingTest {
  @Test
  fun `declared outcomes are read leniently from the first line and kept out of the reply body`() {
    listOf(
      "outcome: refuted.\nThe null check already exists." to "The null check already exists.",
      "Outcome: refuted — the null check already exists" to "the null check already exists",
      "\n**Outcome:** Refuted\nThe null check already exists." to "The null check already exists.",
      "OUTCOME=refuted; the null check already exists" to "the null check already exists",
    ).forEach { (reply, body) ->
      val diagnostics = RecordingDiagnostics()

      val parsed = parseLocalReplyOutcome(reply, emptyList(), diagnostics)

      assertEquals(DiffCommentOutcome.REFUTED to body, parsed, reply)
      assertEquals(emptyList(), diagnostics.warnings, reply)
    }
  }

  @Test
  fun `a reply without an outcome line keeps its text and records the inferred outcome`() {
    val diagnostics = RecordingDiagnostics()

    val parsed = parseLocalReplyOutcome("The outcome: is unclear here.", emptyList(), diagnostics)

    assertEquals(DiffCommentOutcome.SKIPPED to "The outcome: is unclear here.", parsed)
    assertEquals(
      listOf(
        "seam=local_review_reply_outcome value_expected=declared_outcome value_used=skipped " +
          "cause=missing_outcome",
      ),
      diagnostics.warnings,
    )
  }

  private class RecordingDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun info(message: String) = Unit
  }
}
