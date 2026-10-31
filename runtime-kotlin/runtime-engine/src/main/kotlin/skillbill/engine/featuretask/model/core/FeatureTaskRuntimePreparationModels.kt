package skillbill.engine.featuretask.model.core

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact

public sealed interface FeatureTaskRuntimePreparation {
  public data class Prepared(val request: FeatureTaskRuntimeRunRequest) : FeatureTaskRuntimePreparation

  public data class PreparationBlocked(val report: FeatureTaskRuntimeRunReport.Blocked) : FeatureTaskRuntimePreparation
}

internal sealed interface ContinuationRead {
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
