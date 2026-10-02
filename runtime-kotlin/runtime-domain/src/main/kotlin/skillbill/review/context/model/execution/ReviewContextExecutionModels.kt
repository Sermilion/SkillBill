package skillbill.review.context.model.execution

enum class ResolvedReviewExecutionMode { INLINE, DELEGATED }

fun ResolvedReviewExecutionMode.toCodeReviewExecutionMode(): CodeReviewExecutionMode =
  when (this) {
    ResolvedReviewExecutionMode.INLINE -> CodeReviewExecutionMode.INLINE
    ResolvedReviewExecutionMode.DELEGATED -> CodeReviewExecutionMode.DELEGATED
  }

data class ResolvedReviewDepth(
  val resolvedMode: ResolvedReviewExecutionMode,
  val decidingRule: String,
)
