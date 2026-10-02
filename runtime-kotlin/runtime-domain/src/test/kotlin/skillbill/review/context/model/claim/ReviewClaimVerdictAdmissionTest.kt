package skillbill.review.context.model.claim

import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewClaimVerdictAdmissionTest {
  @Test
  fun `a refuted verdict with no file line citation is recorded unresolved and the finding is preserved`() {
    val claim = claim()
    val admitted =
      ReviewClaimVerdictAdmission.admit(
        claim,
        ReviewClaimWorkerResult(claimVerdict = "refuted"),
        RECORDED_AT,
      )
    assertEquals(claim, admitted.claim)
    assertEquals(ReviewClaimVerdict.UNRESOLVED, admitted.verdict.claimVerdict)
    assertEquals(ReviewClaimVerdictAdmission.UNCITED_REFUTATION, admitted.verdict.rejectionReason)
    assertEquals("F-001", admitted.verdict.findingRef)
  }

  @Test
  fun `a result that alters finding text severity or location is rejected and the original claim is kept`() {
    val claim = claim(severity = ParallelReviewSeverity.BLOCKER)
    val admitted =
      ReviewClaimVerdictAdmission.admit(
        claim,
        ReviewClaimWorkerResult(
          claimVerdict = "refuted",
          citations = listOf(ReviewFindingCitation("src/A.kt", 12)),
          severity = ParallelReviewSeverity.NIT.displayName,
          description = "rewritten as a nit",
        ),
        RECORDED_AT,
      )
    assertEquals(claim, admitted.claim)
    assertEquals(ParallelReviewSeverity.BLOCKER, admitted.claim.severity)
    assertEquals(ReviewClaimVerdict.UNRESOLVED, admitted.verdict.claimVerdict)
    assertEquals(ReviewClaimVerdictAdmission.ALTERED_CLAIM, admitted.verdict.rejectionReason)
  }

  @Test
  fun `an unsettled worker result is recorded unresolved and never refuted`() {
    val claim = claim()
    val admitted =
      ReviewClaimVerdictAdmission.admit(
        claim,
        ReviewClaimWorkerResult(claimVerdict = "probably fine"),
        RECORDED_AT,
      )
    assertEquals(claim, admitted.claim)
    assertEquals(ReviewClaimVerdict.UNRESOLVED, admitted.verdict.claimVerdict)
    assertEquals(ReviewClaimVerdictAdmission.UNSETTLED, admitted.verdict.rejectionReason)
  }

  private fun claim(
    severity: ParallelReviewSeverity = ParallelReviewSeverity.MAJOR,
    location: String = "src/A.kt:12",
    description: String = "Null is not checked.",
  ) = ParallelReviewMergedFinding(
    fNumber = "F-001",
    agentIds = listOf("codex"),
    severity = severity,
    confidence = "High",
    location = location,
    description = description,
    repositoryPath = "src/A.kt",
    line = 12,
  )

  private companion object {
    const val RECORDED_AT: String = "2026-08-14T09:00:00Z"
  }
}
