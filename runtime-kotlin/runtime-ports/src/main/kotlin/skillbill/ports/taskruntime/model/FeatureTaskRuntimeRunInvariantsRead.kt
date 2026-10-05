package skillbill.ports.taskruntime.model

import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants

sealed interface FeatureTaskRuntimeRunInvariantsRead {
  data class Read(val invariants: FeatureTaskRuntimeRunInvariants) : FeatureTaskRuntimeRunInvariantsRead

  data class Rejected(val reason: String) : FeatureTaskRuntimeRunInvariantsRead
}
