package skillbill.workflow.verify.model

enum class VerifyReviewMode(val wireValue: String) {
  INLINE("inline"),
  DELEGATED("delegated"),
  ;

  companion object {
    val EXPECTED: String = entries.joinToString("|") { it.wireValue }

    fun fromWire(value: String?): VerifyReviewMode? =
      if (value == null) INLINE else entries.firstOrNull { it.wireValue == value }
  }
}
