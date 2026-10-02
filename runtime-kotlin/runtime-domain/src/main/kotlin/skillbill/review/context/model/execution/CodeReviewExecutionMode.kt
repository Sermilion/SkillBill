package skillbill.review.context.model.execution

enum class CodeReviewExecutionMode(val wireValue: String) {
  AUTO("auto"),
  INLINE("inline"),
  DELEGATED("delegated"),
  ;

  companion object {
    val DEFAULT: CodeReviewExecutionMode = INLINE

    fun fromWire(value: String): CodeReviewExecutionMode =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException(
          "Unknown code-review execution mode '$value'. Allowed: ${entries.joinToString { it.wireValue }}.",
        )
  }
}
