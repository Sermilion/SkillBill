package skillbill.ports.workflow.gitops.model

import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat

data class WorkflowWorktreeNumstatResult(
  val status: WorkflowGitOperationStatus,
  val files: List<GoalObservabilityFileDiffStat>,
  val error: String? = null,
)
