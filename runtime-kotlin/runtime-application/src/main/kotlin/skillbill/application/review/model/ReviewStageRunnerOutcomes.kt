package skillbill.application.review.model

import skillbill.agent.model.PhaseOutput
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewVerificationNonSuccess

internal data class ReviewClaimVerificationOutcome(
  val verdicts: List<ReviewFindingVerdict>,
  val output: PhaseOutput? = null,
  val skipReason: String? = null,
  val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
  val nonSuccess: ReviewVerificationNonSuccess? = null,
)

internal data class ReviewSpecAdjudicationOutcome(
  val verdicts: List<ReviewFindingVerdict>,
  val skipReason: String? = null,
  val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
)
