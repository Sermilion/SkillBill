package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext

sealed interface FeatureTaskRuntimeHandoffProjectionResult {
  data class Accepted(
    val envelope: FeatureTaskRuntimeHandoffEnvelope,
  ) : FeatureTaskRuntimeHandoffProjectionResult

  data class Rejected(
    val context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
  ) : FeatureTaskRuntimeHandoffProjectionResult
}
