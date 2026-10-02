package skillbill.application.review.service

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.context.model.execution.toCodeReviewExecutionMode

object RuntimeOwnedReviewMode {
  private val allowed: List<CodeReviewExecutionMode> =
    listOf(
      CodeReviewExecutionMode.AUTO,
      CodeReviewExecutionMode.INLINE,
    )

  fun parse(value: String): CodeReviewExecutionMode? = allowed.firstOrNull { it.wireValue == value }

  fun unknownModeMessage(value: String): String =
    "Unknown code-review execution mode '$value'. Allowed: ${allowed.joinToString { it.wireValue }}."

  fun execute(mode: CodeReviewExecutionMode): CodeReviewExecutionMode =
    if (mode == CodeReviewExecutionMode.DELEGATED) {
      CodeReviewExecutionMode.INLINE
    } else {
      ReviewExecutionModePolicy.resolve(mode).toCodeReviewExecutionMode()
    }
}
