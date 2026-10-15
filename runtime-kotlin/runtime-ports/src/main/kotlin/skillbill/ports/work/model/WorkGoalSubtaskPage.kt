package skillbill.ports.work.model

import skillbill.workflow.decomposition.model.DecompositionManifest

/** [remainingCount] counts the matching subtasks from the page start on, so `remainingCount > limit` means more follow. */
data class WorkGoalSubtaskPage(
  val manifest: DecompositionManifest,
  val totalCount: Int,
  val currentWorkflowId: String?,
  val remainingCount: Int = totalCount,
)
