package skillbill.review.context.model.execution

import skillbill.review.context.ReviewExecutionModePolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewDepthAutoRuleTest {
  @Test
  fun `auto on pass one resolves inline by the pass-number rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, reviewPassNumber = 1)

    assertEquals(ResolvedReviewExecutionMode.INLINE, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.PASS_NUMBER_RULE}:pass_1_inline", resolved.decidingRule)
  }

  @Test
  fun `auto on a follow-up pass resolves inline by the pass-number rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, reviewPassNumber = 2)

    assertEquals(ResolvedReviewExecutionMode.INLINE, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.PASS_NUMBER_RULE}:pass_n_inline", resolved.decidingRule)
  }

  @Test
  fun `auto without a pass number resolves inline by the named default rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO)

    assertEquals(ResolvedReviewExecutionMode.INLINE, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.DEFAULT_RULE}:inline_default", resolved.decidingRule)
  }

  @Test
  fun `no auto rule reaches the experimental delegated tier`() {
    val resolutions =
      listOf(null, 1, 2, 7).map { pass ->
        ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, reviewPassNumber = pass)
      }

    assertEquals(
      emptyList(),
      resolutions.filter { it.resolvedMode == ResolvedReviewExecutionMode.DELEGATED },
      "auto must never resolve delegated; only an explicit selection may.",
    )
  }

  @Test
  fun `an explicit mode overrides both rules and reports itself as the deciding rule`() {
    val inline = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.INLINE, reviewPassNumber = 1)
    assertEquals(ResolvedReviewExecutionMode.INLINE, inline.resolvedMode)
    assertEquals("explicit_inline_override", inline.decidingRule)

    val delegated = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.DELEGATED, reviewPassNumber = 2)
    assertEquals(ResolvedReviewExecutionMode.DELEGATED, delegated.resolvedMode)
    assertEquals("explicit_delegated_override", delegated.decidingRule)
  }

  @Test
  fun `the default requested mode is the inline tier`() {
    assertEquals(CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.DEFAULT)
  }
}
