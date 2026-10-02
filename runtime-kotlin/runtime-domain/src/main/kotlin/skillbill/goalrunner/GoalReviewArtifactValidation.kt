package skillbill.goalrunner

import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.recordedVerdicts
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifacts

fun DurableWorkflowArtifacts.goalSubtaskReviewArtifacts(): GoalSubtaskReviewArtifacts? =
  GoalSubtaskReviewArtifactDecoder.decode(this)

fun validatedGoalReviewPasses(
  review: GoalSubtaskReviewArtifacts,
  emissionEnvelope: (String) -> FeatureTaskRuntimeWorkflowArtifactMap,
  fetchFindingVerdicts: (String) -> List<ReviewFindingVerdict>,
): List<GoalSubtaskReviewPassResult> {
  review.state.passResults.forEach { pass ->
    val rawResult = review.rawResults.getValue(pass.passNumber.toString())
    val output = emissionEnvelope(rawResult)
    val verdicts = GoalSubtaskReviewSummaryReducer.recordedVerdicts(fetchFindingVerdicts, output)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(output, verdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(output, findings)
    if (
      pass.verdict != outcome.verdict ||
      pass.unresolvedFindingCount != outcome.unresolvedFindingCount ||
      pass.findings != findings
    ) {
      throw InvalidGoalSubtaskReviewStateSchemaError(
        sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
        fieldPath = "pass_results.${pass.passNumber}",
        reason =
          "must exactly match the verdict, unresolved count, and compact findings derived from " +
            "its durable raw review result.",
      )
    }
  }
  return review.state.passResults
}
