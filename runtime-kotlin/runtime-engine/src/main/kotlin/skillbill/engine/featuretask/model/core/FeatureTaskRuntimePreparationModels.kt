package skillbill.engine.featuretask.model.core

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact

sealed interface FeatureTaskRuntimePreparation {
  data class Prepared(val request: FeatureTaskRuntimeRunRequest) : FeatureTaskRuntimePreparation

  data class PreparationBlocked(val report: FeatureTaskRuntimeRunReport.Blocked) : FeatureTaskRuntimePreparation
}

sealed interface ContinuationRead {
  data object None : ContinuationRead

  data class Available(
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    val baseline: GoalSubtaskReviewBaseline,
  ) : ContinuationRead

  data class AvailableWithoutReviewState(
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  ) : ContinuationRead

  data class Failure(val reason: String) : ContinuationRead
}
