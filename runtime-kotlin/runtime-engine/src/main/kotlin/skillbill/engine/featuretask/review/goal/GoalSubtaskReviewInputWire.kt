package skillbill.engine.featuretask.review.goal

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput

public fun goalReviewInputArtifactMap(input: GoalSubtaskReviewInput): Map<String, Any?> =
  linkedMapOf(
    GoalSubtaskReviewInputPayloadKeys.REVIEW_BASE_SHA to input.reviewBaseSha,
    GoalSubtaskReviewInputPayloadKeys.CURRENT_HEAD_SHA to input.currentHeadSha,
    GoalSubtaskReviewInputPayloadKeys.TRACKED_DELTA to input.trackedDelta,
    GoalSubtaskReviewInputPayloadKeys.OWNED_UNTRACKED_PATCHES to input.ownedUntrackedPatches,
  )
