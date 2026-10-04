package skillbill.ports.review.preparation

import skillbill.review.plan.model.ReviewLaunchPlan

interface ReviewAttributionPort {
  fun routedSkillPlatformSlugs(): Map<String, String>

  fun knownPackSkillNames(): Set<String> = routedSkillPlatformSlugs().keys

  fun knownPlatformSlugs(): Set<String> = routedSkillPlatformSlugs().values.toSet()

  /**
   * The composed review lanes for a routed platform pack, so ingestion can attribute a run from the
   * launch plan instead of agent narration. A slug with no installed or in-repo pack yields an empty
   * plan rather than a failure: an unattributable run must still import.
   */
  fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan
}
