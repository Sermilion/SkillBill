package skillbill.review.context.model.execution

enum class CodeReviewExecutionMode(val wireValue: String) {
  AUTO("auto"),
  INLINE("inline"),
  DELEGATED("delegated"),
  ;

  companion object {
    val DEFAULT: CodeReviewExecutionMode = INLINE

    fun fromWireOrNull(value: String): CodeReviewExecutionMode? = entries.firstOrNull { it.wireValue == value }

    fun unknownWireValueMessage(value: String): String =
      "Unknown code-review execution mode '$value'. Allowed: ${entries.joinToString { it.wireValue }}."

    fun fromWire(value: String): CodeReviewExecutionMode =
      fromWireOrNull(value) ?: throw IllegalArgumentException(unknownWireValueMessage(value))
  }
}
