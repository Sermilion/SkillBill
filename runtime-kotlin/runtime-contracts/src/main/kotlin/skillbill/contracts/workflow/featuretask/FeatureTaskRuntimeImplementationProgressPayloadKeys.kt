package skillbill.contracts.workflow.featuretask

import skillbill.contracts.SharedPayloadKeys

object FeatureTaskRuntimeImplementationProgressPayloadKeys {
  const val CONTRACT_VERSION: String = SharedPayloadKeys.CONTRACT_VERSION
  const val CRITERION_IDS: String = "criterion_ids"
  const val CURRENT_INDEX: String = "current_index"
  const val WORKER_COUNT: String = "worker_count"
  const val LAUNCH_COUNT: String = "launch_count"
  const val CLOSED_IDS: String = "closed_ids"
}
