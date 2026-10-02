package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeReviewPlanningFailureLaunchTest {
  @Test
  fun `an unresolved diff blocks needing user action with the child-owned diff prefix`() {
    val launch = planningFailureLaunch(ParallelCodeReviewPlanningFailure.DiffUnresolved("no diff for base..head"))

    assertEquals(
      ReviewPassLaunch.Failed(
        "Runtime-owned review could not resolve the child-owned diff: no diff for base..head",
        FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      ),
      launch,
    )
  }

  @Test
  fun `an invalid usage blocks as retryable with the review-failed prefix`() {
    val launch = planningFailureLaunch(ParallelCodeReviewPlanningFailure.UsageInvalid("unsupported agent"))

    assertEquals(
      ReviewPassLaunch.Failed(
        "Runtime-owned review failed: unsupported agent",
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      ),
      launch,
    )
  }
}
