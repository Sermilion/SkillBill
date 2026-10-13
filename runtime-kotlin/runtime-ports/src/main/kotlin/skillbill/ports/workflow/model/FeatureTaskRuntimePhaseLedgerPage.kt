package skillbill.ports.workflow.model

import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry

data class FeatureTaskRuntimePhaseLedgerPage(
  val entries: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val nextOffset: Int?,
)
