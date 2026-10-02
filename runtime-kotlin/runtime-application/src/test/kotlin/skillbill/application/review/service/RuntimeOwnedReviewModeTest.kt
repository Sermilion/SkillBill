package skillbill.application.review.service

import skillbill.review.context.model.execution.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeOwnedReviewModeTest {
  @Test
  fun `parse accepts auto and inline`() {
    assertEquals(CodeReviewExecutionMode.AUTO, RuntimeOwnedReviewMode.parse("auto"))
    assertEquals(CodeReviewExecutionMode.INLINE, RuntimeOwnedReviewMode.parse("inline"))
  }

  @Test
  fun `parse unknown modes list only auto and inline`() {
    listOf("delegated", "external").forEach { value ->
      assertNull(RuntimeOwnedReviewMode.parse(value))
      assertEquals(
        "Unknown code-review execution mode '$value'. Allowed: auto, inline.",
        RuntimeOwnedReviewMode.unknownModeMessage(value),
      )
    }
  }

  @Test
  fun `execute pins leftover delegated invariants to inline`() {
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      RuntimeOwnedReviewMode.execute(CodeReviewExecutionMode.DELEGATED),
    )
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      RuntimeOwnedReviewMode.execute(CodeReviewExecutionMode.AUTO),
    )
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      RuntimeOwnedReviewMode.execute(CodeReviewExecutionMode.INLINE),
    )
  }
}
