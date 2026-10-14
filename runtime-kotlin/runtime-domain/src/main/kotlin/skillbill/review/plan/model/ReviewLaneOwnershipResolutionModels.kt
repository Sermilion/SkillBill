package skillbill.review.plan.model

data class ReviewLaneOwnershipConflict(
  val area: String,
  val owners: List<String>,
  val fallbackOwner: String,
  val reason: String,
)

data class ReviewLaneOwnershipResult(
  val lanes: List<ReviewReconciledLane>,
  val conflicts: List<ReviewLaneOwnershipConflict> = emptyList(),
)
