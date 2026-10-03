package skillbill.review.parsing

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.reviewAggregationIntegrityError
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewLaneAggregationInput
import skillbill.review.model.ReviewLaneReviewDisposition

object ReviewLaneAggregation {
  fun requireCompleteLaneResults(
    expectedLanes: Collection<String>,
    results: List<ReviewLaneAggregationInput>,
    commitSequenceDigest: String,
  ): ReviewCoverageReport {
    val integrityFailure = laneIntegrityFailure(expectedLanes, results, commitSequenceDigest)
    if (integrityFailure != null) throw integrityFailure
    return ReviewCoverageReport(
      cleanLanes =
        results.filter { it.disposition == ReviewLaneReviewDisposition.COMPLETE }
          .map { it.lane }.sorted(),
      incompleteLanes = results.filter { it.disposition == ReviewLaneReviewDisposition.INCOMPLETE },
      integrationCompleted = false,
    )
  }

  private fun laneIntegrityFailure(
    expectedLanes: Collection<String>,
    results: List<ReviewLaneAggregationInput>,
    commitSequenceDigest: String,
  ): SkillBillRuntimeException? {
    val duplicates = results.groupBy { it.lane }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
      return reviewAggregationIntegrityError("a lane reported more than one result", duplicates.toList())
    }
    val byLane = results.associateBy { it.lane }
    val missing = expectedLanes.filterNot { it in byLane }
    if (missing.isNotEmpty()) {
      return reviewAggregationIntegrityError("a selected lane produced no result", missing)
    }
    val foreign = results.map { it.lane }.filterNot { it in expectedLanes }
    if (foreign.isNotEmpty()) {
      return reviewAggregationIntegrityError("a result names a lane that was never selected", foreign)
    }
    val mismatched = results.filter { it.commitSequenceDigest != commitSequenceDigest }.map { it.lane }
    return when {
      mismatched.isNotEmpty() ->
        reviewAggregationIntegrityError(
          "a result was minted against a different commit sequence than the one under review",
          mismatched,
        )
      else -> null
    }
  }
}
