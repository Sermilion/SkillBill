package skillbill.engine.goalrunner.status

import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.model.GoalRunnerRunningReviewLane
import skillbill.goalrunner.model.GoalRunnerRunningReviewPass
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal fun runningReviewPass(
  record: FeatureTaskRuntimePhaseRecord?,
  reviewState: GoalSubtaskReviewState?,
  database: DatabaseSessionFactory,
  degradation: SubtaskReadDegradation,
): GoalRunnerRunningReviewPass? {
  if (record?.status != WorkflowStepStatus.RUNNING) return null
  val passNumber =
    record.reviewPassNumber
      ?: reviewState?.reservedPassNumber
      ?: ((reviewState?.passResults?.maxOfOrNull { it.passNumber } ?: 0) + 1)
  if (reviewState?.passResults.orEmpty().any { it.passNumber == passNumber }) return null
  val runId = record.reviewRunId
  val lanes =
    if (runId.isNullOrBlank()) {
      emptyList()
    } else {
      catchingNonCancellation {
        database.read { unit -> unit.reviews.fetchReviewRunLanes(runId) }
          .sortedBy { it.orderIndex }
          .map { lane ->
            GoalRunnerRunningReviewLane(
              name = lane.laneSkillName,
              finished = lane.reviewDisposition == ReviewLaneReviewDisposition.COMPLETE,
            )
          }
      }.getOrElse { error ->
        degradation.record(
          source = GoalRunnerSubtaskReadSource.REVIEW_LANES,
          expected = "running_review_lanes",
          used = "omitted",
          error = error,
        )
        emptyList()
      }
    }
  return GoalRunnerRunningReviewPass(passNumber, lanes)
}
