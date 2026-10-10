package skillbill.review.eval

internal const val REVIEW_EVAL_DEFAULT_LINE_WINDOW: Int = 5
internal const val REVIEW_EVAL_UNATTRIBUTED_LANE: String = "unattributed"
internal const val REVIEW_EVAL_OVERALL_LANE: String = "overall"
internal const val REVIEW_EVAL_PLACEHOLDER_RUN_ID: String = "rvw-eval-placeholder"
internal const val REVIEW_EVAL_PLACEHOLDER_SESSION_ID: String = "rvs-eval-placeholder"
internal const val REVIEW_EVAL_REGISTER_DIR_ENV: String = "SKILL_BILL_REVIEW_EVAL_REGISTER_DIR"

internal enum class ReviewEvalExpectedLabel {
  TRUE_POSITIVE,
  NON_ISSUE,
}

internal enum class ReviewEvalExpectedStatus {
  READY,
  NEEDS_CURATION,
}

internal data class ReviewEvalExpectedFinding(
  val id: String,
  val file: String,
  val lineStart: Int?,
  val lineEnd: Int?,
  val lane: String,
  val label: ReviewEvalExpectedLabel,
  val status: ReviewEvalExpectedStatus = ReviewEvalExpectedStatus.READY,
  val window: Int = REVIEW_EVAL_DEFAULT_LINE_WINDOW,
)

internal data class ReviewEvalLocatedFinding(
  val findingId: String,
  val file: String,
  val lineStart: Int,
  val lineEnd: Int,
  val lane: String,
)

internal data class ReviewEvalCurationItem(
  val reason: String,
  val findingId: String? = null,
  val detail: String = "",
)

internal data class ReviewEvalMatch(
  val reported: ReviewEvalLocatedFinding,
  val expected: ReviewEvalExpectedFinding,
)

internal data class ReviewEvalLaneScore(
  val lane: String,
  val truePositiveCount: Int,
  val falsePositiveCount: Int,
  val matchedExpectedTruePositiveCount: Int,
  val expectedTruePositiveCount: Int,
  val precision: Double,
  val recall: Double,
)

internal data class ReviewEvalScore(
  val truePositives: List<ReviewEvalMatch>,
  val missedTruePositives: List<ReviewEvalExpectedFinding>,
  val falsePositives: List<ReviewEvalMatch>,
  val unlabeledFindings: List<ReviewEvalLocatedFinding>,
  val excludedNeedsCuration: List<ReviewEvalExpectedFinding>,
  val curationItems: List<ReviewEvalCurationItem>,
  val overall: ReviewEvalLaneScore,
  val perLane: Map<String, ReviewEvalLaneScore>,
  val partial: Boolean,
)

internal data class ReviewEvalRegisterRead(
  val located: List<ReviewEvalLocatedFinding>,
  val curationItems: List<ReviewEvalCurationItem>,
)

internal data class ReviewEvalMatchSet(
  val matches: List<ReviewEvalMatch>,
  val unlabeled: List<ReviewEvalLocatedFinding>,
  val unmatchedExpected: List<ReviewEvalExpectedFinding>,
)
