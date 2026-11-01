package skillbill.engine.featuretask.review.goal

internal object GoalSubtaskReviewInputPayloadKeys {
  const val REVIEW_BASE_SHA: String = "review_base_sha"
  const val CURRENT_HEAD_SHA: String = "current_head_sha"
  const val TRACKED_DELTA: String = "tracked_delta"
  const val OWNED_UNTRACKED_PATCHES: String = "owned_untracked_patches"
}
