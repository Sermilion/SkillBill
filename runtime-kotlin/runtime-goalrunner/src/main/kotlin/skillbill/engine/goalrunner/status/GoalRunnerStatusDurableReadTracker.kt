package skillbill.engine.goalrunner.status

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.model.GoalRunnerRunningReviewLane
import skillbill.goalrunner.model.GoalRunnerRunningReviewPass
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal object GoalRunnerSubtaskReadSource {
  const val PHASE_RESULTS = "subtask_phase_results"
  const val REVIEW_RAW_RESULTS = "subtask_review_raw_results"
  const val REVIEW_FINDINGS = "subtask_review_findings"
  const val REVIEW_LANES = "subtask_review_lanes"
  const val SUBTASK_AUDIT_CRITERIA = "subtask_audit_criteria"
  const val SUBTASK_AUDIT_HISTORY = "subtask_audit_history"
  const val PHASE_CARD_OUTPUT = "subtask_phase_card_output"
}

internal class SubtaskReadDegradation(
  private val tracker: GoalRunnerStatusDurableReadTracker,
) {
  var source: String? = null
    private set

  fun record(
    source: String,
    expected: String,
    used: String,
    error: Throwable? = null,
  ) {
    tracker.recordDegradedRead("goal-status.$source", expected, used, error)
    if (this.source == null) this.source = source
  }
}

internal class GoalRunnerStatusDurableReadTracker(
  private val diagnostics: RuntimeDiagnostics,
) {
  var degraded: Boolean = false
    private set

  fun recordDegradedRead(
    seam: String,
    expected: String,
    used: String,
    error: Throwable? = null,
  ) {
    degraded = true
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=$seam value_expected=$expected value_used=$used",
      error,
    )
  }
}

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
