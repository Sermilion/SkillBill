package skillbill.engine.featuretask.runloop.state

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost

internal fun PhaseAttemptRunHost.recordReviewRunForAcceptedStep(
  reviewRunId: String,
  result: ParallelCodeReviewResult,
  laneTelemetryRecorded: Boolean,
) {
  recordReviewRunForRunStatePorts(reviewRunId, result, laneTelemetryRecorded)
}

internal fun PhaseAttemptRunHost.pinnedReviewTargetForAcceptedStep(resolve: () -> ReviewTarget): ReviewTarget =
  pinnedReviewTargetForRunStatePorts(resolve)
