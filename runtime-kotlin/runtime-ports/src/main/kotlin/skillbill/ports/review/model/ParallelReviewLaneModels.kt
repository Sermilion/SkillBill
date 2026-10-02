package skillbill.ports.review.model

import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.packet.ReviewLaneSegmentAccounting
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.ReviewLaneReviewDisposition

data class ParallelReviewLaneRunResult(
  val lane1: ParallelReviewLaneOutcome,
)

data class ParallelReviewLaneOutcome(
  val success: Boolean,
  val rawOutput: String,
  val failureReason: String? = null,
  val droppedCandidateDiagnostic: String? = null,
  val budgetOutcome: ReviewBudgetOutcome? = null,
  val accounting: ReviewLaneAccounting? = null,
  val specialistAccounting: List<ReviewLaneAccounting> = accounting?.let(::listOf) ?: emptyList(),
  val findings: List<ParallelReviewRawFinding> = emptyList(),
  val interrupted: Boolean = false,
  val reviewDisposition: ReviewLaneReviewDisposition? = null,
  val bundleCompositionDigest: String? = null,
  val segmentAccounting: List<ReviewLaneSegmentAccounting> = emptyList(),
  val unreviewedSegmentIds: List<String> = emptyList(),
  val budgetDimension: String? = null,
  val unreviewedUnits: List<String> = emptyList(),
  val rejectedCandidateCount: Int = 0,
  val unboundSeam: String? = null,
  val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
)
