package skillbill.ports.db.model

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity

data class RuntimeWorkerLease(
  val holder: String,
  val process: FeatureTaskRuntimeProcessIdentity,
)
