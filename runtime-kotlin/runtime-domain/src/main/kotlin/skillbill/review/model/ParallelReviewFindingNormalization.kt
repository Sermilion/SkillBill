package skillbill.review.model

enum class ParallelReviewFindingNormalizationField(val wireValue: String) {
  SEVERITY("severity"),
  CONFIDENCE("confidence"),
}

data class ParallelReviewFindingNormalization(
  val lineText: String,
  val linePosition: Int,
  val field: ParallelReviewFindingNormalizationField,
  val rawValue: String,
  val normalizedValue: String,
)
