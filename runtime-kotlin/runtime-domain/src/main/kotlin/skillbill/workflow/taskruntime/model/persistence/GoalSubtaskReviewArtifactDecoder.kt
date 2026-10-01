package skillbill.workflow.taskruntime.model.persistence

import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.model.goalobservability.asGoalWorkflowArtifactMap
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.reviewStateError
import skillbill.workflow.model.goalreview.toReviewStateMap

data class GoalSubtaskReviewArtifacts(
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val state: GoalSubtaskReviewState,
  val rawResults: Map<String, String>,
)

object GoalSubtaskReviewArtifactDecoder {
  fun decode(artifacts: Any): GoalSubtaskReviewArtifacts? =
    decodeWire(artifacts.asGoalWorkflowArtifactMap("goal subtask review artifacts"))

  fun decodeContinuationOnly(artifacts: Any): FeatureTaskRuntimeGoalContinuationArtifact? =
    decodeContinuationOnlyWire(artifacts.asGoalWorkflowArtifactMap("goal subtask review continuation artifacts"))

  fun decodeReviewStateOnly(artifacts: Any): GoalSubtaskReviewState? =
    decodeReviewStateOnlyWire(artifacts.asGoalWorkflowArtifactMap("goal subtask review state artifacts"))

  internal fun decodeWire(artifacts: Map<String, Any?>): GoalSubtaskReviewArtifacts? {
    val hasContinuation = FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY in artifacts
    val hasState = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY in artifacts
    if (!hasContinuation && !hasState) {
      if (GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY in artifacts) {
        reviewStateError(
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
          "must be absent when no goal-subtask review child state exists.",
        )
      }
      return null
    }
    if (!hasContinuation) {
      reviewStateError(
        FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
        "must be present whenever a goal-subtask review state exists.",
      )
    }
    if (!hasState) {
      reviewStateError(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
        "must be present whenever a goal-continuation child exists.",
      )
    }
    val continuation =
      try {
        FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(
          artifacts.getValue(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY).toReviewStateMap(
            FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
          ),
        )
      } catch (error: InvalidWorkflowStateSchemaError) {
        reviewStateError(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY, error.message.orEmpty(), error)
      }
    val state =
      GoalSubtaskReviewState.fromArtifactMap(
        artifacts.getValue(GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY).toReviewStateMap(
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
        ),
      )
    if (state.codeReviewMode != continuation.codeReviewMode) {
      reviewStateError(
        "$GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY.code_review_mode",
        "must match the immutable goal-continuation review policy.",
      )
    }
    return GoalSubtaskReviewArtifacts(
      continuation = continuation,
      state = state,
      rawResults = rawResults(artifacts, state),
    )
  }

  internal fun decodeContinuationOnlyWire(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? {
    if (FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY !in artifacts) {
      if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY in artifacts) {
        reviewStateError(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
          "must be present whenever a goal-subtask review state exists.",
        )
      }
      if (GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY in artifacts) {
        reviewStateError(
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
          "must be absent when no goal-subtask review child state exists.",
        )
      }
      return null
    }
    return try {
      decodeWire(artifacts)?.continuation
    } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
      if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY !in artifacts) decodeContinuationDirect(artifacts) else throw error
    }
  }

  internal fun decodeReviewStateOnlyWire(artifacts: Map<String, Any?>): GoalSubtaskReviewState? =
    if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY !in artifacts) null else decodeWire(artifacts)?.state

  private fun decodeContinuationDirect(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact =
    try {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(
        artifacts.getValue(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY).toReviewStateMap(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
        ),
      )
    } catch (error: InvalidWorkflowStateSchemaError) {
      reviewStateError(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY, error.message.orEmpty(), error)
    }

  private fun rawResults(
    artifacts: Map<String, Any?>,
    state: GoalSubtaskReviewState,
  ): Map<String, String> {
    if (state.completedPassCount == 0) {
      val cleared =
        artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY]
          ?.toReviewStateMap(GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY)
          .orEmpty()
      if (cleared.isNotEmpty()) {
        reviewStateError(
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
          "must hold no durable raw review result before the first completed review pass.",
        )
      }
      return emptyMap()
    }
    val raw =
      artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY]
        ?.toReviewStateMap(GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY)
        ?: reviewStateError(
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
          "must contain the durable raw review result for every completed pass.",
        )
    val expectedKeys = state.passResults.map { result -> result.passNumber.toString() }.toSet()
    if (raw.keys != expectedKeys) {
      reviewStateError(
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
        "must contain exactly one durable raw review result for every completed pass.",
      )
    }
    return raw.mapValues { (passNumber, value) ->
      (value as? String)?.takeIf(String::isNotBlank)
        ?: reviewStateError(
          "$GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY.$passNumber",
          "must be a non-blank durable raw review result.",
        )
    }
  }
}
