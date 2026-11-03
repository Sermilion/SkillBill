package skillbill.ports.workchanges.model

import skillbill.workflow.model.DecompositionStatus

data class WorkScopeSubtask(
  val id: Int,
  val name: String,
  val status: DecompositionStatus,
  val running: Boolean,
  val startSha: String?,
  val endSha: String?,
  val baselineUntrackedPaths: List<String>,
)

sealed interface WorkGoalScopesResult {
  data class Listed(
    val worktreeRoot: String,
    val targetBranch: String?,
    val rangesSupported: Boolean,
    val subtasks: List<WorkScopeSubtask>,
    val committedHeadBranch: String? = null,
  ) : WorkGoalScopesResult

  data class NotAGoal(val workflowId: String) : WorkGoalScopesResult

  data class Unavailable(val reason: String) : WorkGoalScopesResult
}
