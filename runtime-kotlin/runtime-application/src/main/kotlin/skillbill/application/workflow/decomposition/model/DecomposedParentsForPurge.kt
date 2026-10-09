package skillbill.application.workflow.decomposition.model

import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.model.DecompositionManifest

data class DecomposedParentForPurge(
  val record: WorkflowStateRecord,
  val manifest: DecompositionManifest?,
)

data class UnclassifiedPurgeRow(
  val workflowId: String,
  val reason: String,
)

data class DecomposedParentsForPurge(
  val parents: List<DecomposedParentForPurge>,
  val unclassifiedRows: List<UnclassifiedPurgeRow>,
)
