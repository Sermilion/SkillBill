package skillbill.review.context.model.launch

import skillbill.review.context.model.execution.SpecIntentProjection
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingCitationDiagnostic
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewStage

data class ReviewSpecAdjudicationWorkerResult(
  val scopeDisposition: String? = null,
  val dispositionValues: List<String> = emptyList(),
  val citedSpecElement: String? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val citationDiagnostics: List<ReviewFindingCitationDiagnostic> = emptyList(),
  val severityAdjustmentDirection: String? = null,
  val severityAdjustmentJustification: String? = null,
  val adjustedSeverity: String? = null,
  val findingRef: String? = null,
  val severity: String? = null,
  val location: String? = null,
  val description: String? = null,
)

object ReviewSpecAdjudicationAdmission {
  const val UNCITED_DOWNGRADE: String = "severity downgrade requires a cited spec element"
  const val UNCITED_RAISE: String = "severity raise requires a cited spec element"
  const val UNCITED_OUT_OF_SCOPE: String = "out_of_scope_preexisting requires a cited spec element"
  const val SPEC_DEVIATION_NOT_CONSTRAINT: String =
    "spec_deviation requires a cited constraint or non-goal present in the projection"
  const val UNKNOWN_SPEC_ELEMENT: String = "cited spec element is not present in the projection"
  const val AMBIGUOUS: String = "worker did not return exactly one scope disposition"
  const val ALTERED_CLAIM: String = "worker result altered the finding claim"
  const val UNSETTLED: String = "worker did not settle the disposition"

  fun admit(
    claim: ParallelReviewMergedFinding,
    stage1: ReviewFindingVerdict,
    projection: SpecIntentProjection,
    worker: ReviewSpecAdjudicationWorkerResult?,
    recordedAt: String,
  ): ReviewFindingVerdict {
    if (worker == null || claimAltered(claim, worker)) {
      return inScope(claim, stage1, recordedAt, if (worker == null) UNSETTLED else ALTERED_CLAIM)
    }
    val requested =
      uniqueDisposition(worker)
        ?.let(ReviewScopeDisposition::fromWireOrNull)
        ?: return inScope(claim, stage1, recordedAt, AMBIGUOUS)
    val cited = citedSpec(worker, projection)
    val requestedAdjustment = parsedAdjustment(worker)
    val rejection = dispositionRejection(requested, cited, requestedAdjustment)
    return if (rejection != null) {
      inScope(claim, stage1, recordedAt, rejection)
    } else {
      ReviewFindingVerdict(
        stage = ReviewStage.ADJUDICATION,
        findingRef = claim.fNumber,
        claimVerdict = stage1.claimVerdict,
        scopeDisposition = requested,
        citations = worker.citations,
        severityAdjustment = requestedAdjustment,
        recordedAt = recordedAt,
      )
    }
  }

  private fun uniqueDisposition(worker: ReviewSpecAdjudicationWorkerResult): String? {
    val values =
      (listOfNotNull(worker.scopeDisposition?.trim()?.takeIf { it.isNotEmpty() }) + worker.dispositionValues)
        .map { it.trim().lowercase() }
        .distinct()
    return values.singleOrNull()
  }

  private fun parsedAdjustment(worker: ReviewSpecAdjudicationWorkerResult): ReviewSeverityAdjustment? {
    val direction =
      worker.severityAdjustmentDirection?.trim()?.lowercase()?.let(ReviewSeverityAdjustmentDirection::fromWireOrNull)
        ?: return null
    val justification =
      listOfNotNull(
        worker.adjustedSeverity?.trim()?.takeIf { it.isNotEmpty() },
        worker.severityAdjustmentJustification?.trim()?.takeIf { it.isNotEmpty() },
      ).joinToString(": ")
    if (justification.isBlank()) return null
    return ReviewSeverityAdjustment(direction, justification)
  }

  private fun claimAltered(
    claim: ParallelReviewMergedFinding,
    worker: ReviewSpecAdjudicationWorkerResult,
  ): Boolean =
    (worker.findingRef != null && worker.findingRef != claim.fNumber) ||
      (worker.severity != null && worker.severity != claim.severity.displayName) ||
      (worker.location != null && worker.location != claim.location) ||
      (worker.description != null && worker.description != claim.description)

  private data class CitedSpec(
    val citationsEmpty: Boolean,
    val citedElement: String?,
    val projectionHit: Boolean,
    val constraintHit: Boolean,
  )

  private fun citedSpec(
    worker: ReviewSpecAdjudicationWorkerResult,
    projection: SpecIntentProjection,
  ): CitedSpec {
    val citedElement = worker.citedSpecElement?.trim()?.takeIf { it.isNotEmpty() }
    return CitedSpec(
      citationsEmpty = worker.citations.isEmpty(),
      citedElement = citedElement,
      projectionHit = citedElement != null && projection.namedElements().any { it == citedElement },
      constraintHit =
        citedElement != null &&
          (citedElement in projection.constraints || citedElement in projection.nonGoals),
    )
  }

  private fun dispositionRejection(
    requested: ReviewScopeDisposition,
    cited: CitedSpec,
    adjustment: ReviewSeverityAdjustment?,
  ): String? =
    when {
      requested == ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING &&
        (cited.citationsEmpty || cited.citedElement == null) -> UNCITED_OUT_OF_SCOPE
      requested == ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING && !cited.projectionHit ->
        UNKNOWN_SPEC_ELEMENT
      requested == ReviewScopeDisposition.SPEC_DEVIATION && !cited.constraintHit ->
        SPEC_DEVIATION_NOT_CONSTRAINT
      adjustment != null && (cited.citationsEmpty || cited.citedElement == null) ->
        uncitedAdjustmentReason(adjustment)
      adjustment != null && !cited.projectionHit -> UNKNOWN_SPEC_ELEMENT
      else -> null
    }

  private fun uncitedAdjustmentReason(adjustment: ReviewSeverityAdjustment): String =
    if (adjustment.direction == ReviewSeverityAdjustmentDirection.LOWER) {
      UNCITED_DOWNGRADE
    } else {
      UNCITED_RAISE
    }

  private fun inScope(
    claim: ParallelReviewMergedFinding,
    stage1: ReviewFindingVerdict,
    recordedAt: String,
    reason: String,
  ): ReviewFindingVerdict =
    ReviewFindingVerdict(
      stage = ReviewStage.ADJUDICATION,
      findingRef = claim.fNumber,
      claimVerdict = stage1.claimVerdict,
      scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
      recordedAt = recordedAt,
      rejectionReason = reason,
    )
}

private fun SpecIntentProjection.namedElements(): List<String> = constraints + nonGoals + deferredItems
