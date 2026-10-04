package skillbill.application.review.service

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.preparation.ReviewAttributionPort
import skillbill.review.attribution.canonicalPackSkillNames
import skillbill.review.attribution.canonicalPlatformSlugs
import skillbill.review.attribution.packSlugFromCanonicalPackSkillName
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewRunLane
import skillbill.review.parsing.ReviewRunLaneResolver
import skillbill.review.plan.model.ReviewLaunchPlan

internal fun composedRunLanes(
  review: ImportedReview,
  reviewAttributionPort: ReviewAttributionPort,
  diagnostics: RuntimeDiagnostics,
): List<ReviewRunLane> {
  val routedPackSlug = packSlugFromCanonicalPackSkillName(review.routedSkillCanonical)
  if (routedPackSlug == null) {
    diagnostics.warning(
      "review lane composition: routed skill '${review.routedSkillCanonical}' names no platform pack; " +
        "run ${review.reviewRunId} imports with unresolved lanes.",
    )
    return ReviewRunLaneResolver.resolve(
      ReviewLaunchPlan(review.routedSkillCanonical, emptyList()),
      review.specialistReviews,
    )
  }
  val plan =
    try {
      reviewAttributionPort.composedLaunchPlan(routedPackSlug)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      diagnostics.warning(
        "review lane composition: pack '$routedPackSlug' failed to compose " +
          "(${error.failureCodeLabel() ?: error::class.simpleName}); " +
          "run ${review.reviewRunId} imports with unresolved lanes " +
          "instead of the composed launch plan.",
        error,
      )
      ReviewLaunchPlan(routedPackSlug, emptyList())
    }
  return ReviewRunLaneResolver.resolve(plan, review.specialistReviews)
}

internal fun canonicalAttributionPorts(reviewAttributionPort: ReviewAttributionPort) =
  reviewAttributionPort.knownPackSkillNames() + canonicalPackSkillNames to
    reviewAttributionPort.knownPlatformSlugs() + canonicalPlatformSlugs
