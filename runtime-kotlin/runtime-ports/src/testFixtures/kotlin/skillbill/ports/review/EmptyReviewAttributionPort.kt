package skillbill.ports.review

import skillbill.ports.review.preparation.ReviewAttributionPort
import skillbill.review.plan.model.ReviewLaunchPlan

object EmptyReviewAttributionPort : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> = emptyMap()

  override fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan =
    ReviewLaunchPlan(routedPackSlug, emptyList())
}
