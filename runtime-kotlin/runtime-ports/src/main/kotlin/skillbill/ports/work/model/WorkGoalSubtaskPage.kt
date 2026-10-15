package skillbill.ports.work.model

import skillbill.workflow.decomposition.model.DecompositionManifest

data class WorkGoalSubtaskPage(
  val manifest: DecompositionManifest,
  val totalCount: Int,
  val currentWorkflowId: String?,
  val remainingCount: Int = totalCount,
)
