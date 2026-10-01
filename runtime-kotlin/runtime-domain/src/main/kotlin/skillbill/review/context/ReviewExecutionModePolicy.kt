package skillbill.review.context

import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.context.model.execution.ResolvedReviewDepth
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode

object ReviewExecutionModePolicy {
  const val PASS_NUMBER_RULE: String = "auto_mode_by_pass_number"
  const val DEFAULT_RULE: String = "auto_mode_default"

  const val FIRST_REVIEW_PASS: Int = 1

  fun resolve(requested: CodeReviewExecutionMode): ResolvedReviewExecutionMode = resolveWithRule(requested).resolvedMode

  fun resolveWithRule(
    requested: CodeReviewExecutionMode,
    reviewPassNumber: Int? = null,
  ): ResolvedReviewDepth =
    when (requested) {
      CodeReviewExecutionMode.INLINE ->
        ResolvedReviewDepth(
          ResolvedReviewExecutionMode.INLINE,
          "explicit_inline_override",
        )
      CodeReviewExecutionMode.DELEGATED ->
        ResolvedReviewDepth(
          ResolvedReviewExecutionMode.DELEGATED,
          "explicit_delegated_override",
        )
      CodeReviewExecutionMode.AUTO ->
        reviewPassNumber?.let(::resolveAutoByPassNumber)
          ?: ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$DEFAULT_RULE:inline_default")
    }

  private fun resolveAutoByPassNumber(passNumber: Int): ResolvedReviewDepth =
    if (passNumber == FIRST_REVIEW_PASS) {
      ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$PASS_NUMBER_RULE:pass_1_inline")
    } else {
      ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$PASS_NUMBER_RULE:pass_n_inline")
    }
}
