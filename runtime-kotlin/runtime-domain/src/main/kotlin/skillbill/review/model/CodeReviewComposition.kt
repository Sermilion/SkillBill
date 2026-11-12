package skillbill.review.model

enum class CodeReviewCompositionScope(val wireValue: String) {
  SameReviewScope("same-review-scope"),
  ;

  companion object {
    fun fromWireValue(value: String): CodeReviewCompositionScope? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class CodeReviewCompositionMode(val wireValue: String) {
  KmpBaseline("kmp-baseline"),
  ;

  companion object {
    fun fromWireValue(value: String): CodeReviewCompositionMode? = entries.firstOrNull { it.wireValue == value }
  }
}

data class CodeReviewBaselineLayer(
  val platform: String,
  val skill: String,
  val scope: CodeReviewCompositionScope,
  val required: Boolean,
  val mode: CodeReviewCompositionMode,
)

data class CodeReviewComposition(
  val baselineLayers: List<CodeReviewBaselineLayer>,
)
