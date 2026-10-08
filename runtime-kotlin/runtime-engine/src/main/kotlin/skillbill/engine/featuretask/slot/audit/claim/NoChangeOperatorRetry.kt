package skillbill.engine.featuretask.slot.audit.claim

import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause

internal data class NoChangeOperatorRetry(
  val instructions: String,
  val pause: FeatureTaskRuntimeNoChangePause,
)
