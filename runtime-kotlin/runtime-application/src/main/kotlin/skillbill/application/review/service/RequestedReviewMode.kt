package skillbill.application.review.service

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode

object RequestedReviewMode {
  val defaultWireValue: String = CodeReviewExecutionMode.DEFAULT.wireValue
  val inlineWireValue: String = CodeReviewExecutionMode.INLINE.wireValue
  val delegatedWireValue: String = CodeReviewExecutionMode.DELEGATED.wireValue
  val autoWireValue: String = CodeReviewExecutionMode.AUTO.wireValue

  fun parse(value: String): CodeReviewExecutionMode = validate(CodeReviewExecutionMode.fromWire(value))

  fun isKnown(value: String): Boolean = CodeReviewExecutionMode.entries.any { it.wireValue == value }

  fun isDelegated(mode: CodeReviewExecutionMode): Boolean = mode == CodeReviewExecutionMode.DELEGATED

  fun validate(mode: CodeReviewExecutionMode): CodeReviewExecutionMode = mode.also(ReviewExecutionModePolicy::resolve)
}
