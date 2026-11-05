package skillbill.engine.operation.prreviewfix

internal data class PrReviewFixTarget(
  val reference: String?,
  val instructions: String?,
)

internal enum class PrReviewFixMode(val wireValue: String) {
  LOCAL("local"),
  REMOTE("remote"),
  ;

  companion object {
    fun from(value: String?): PrReviewFixMode? =
      if (value == null) LOCAL else entries.firstOrNull { it.wireValue == value }
  }
}
