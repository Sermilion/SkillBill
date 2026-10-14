package skillbill.review.plan.model

data class ReviewRoutingChangedFile(val path: String, val changedContent: String)

data class ReviewStackRoutingResult(
  val routedSlugs: Set<String>,
  val ownedPathsBySlug: Map<String, Set<String>>,
  val missingPackFallbacks: List<ReviewStackRoutingFallback> = emptyList(),
  val ownershipConflicts: List<ReviewStackRoutingConflict> = emptyList(),
)

data class ReviewStackRoutingConflict(
  val path: String,
  val owners: List<String>,
  val fallbackSlug: String?,
)

data class ReviewStackRoutingFallback(
  val routedSlug: String,
  val fallbackSlug: String,
  val missingPlatforms: List<String>,
)
