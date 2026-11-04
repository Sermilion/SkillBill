package skillbill.ports.workchanges.model

import skillbill.workflow.model.DecompositionStatus

data class WorkScopeSubtask(
  val id: Int,
  val name: String,
  val status: DecompositionStatus,
  val running: Boolean,
  val recordedSha: String?,
  val baselineUntrackedPaths: List<String>,
  val reviewBaseSha: String? = null,
)

data class WorkTimelineCommit(
  val sha: String,
  val parentSha: String,
  val subject: String,
  val ownerSubtaskIds: List<Int>,
)

sealed interface WorkGoalTimelineResult {
  data class Attributed(
    val mergeBaseSha: String,
    val headSha: String,
    val commits: List<WorkTimelineCommit>,
    val changedAfterFinishIds: Set<Int>,
  ) : WorkGoalTimelineResult

  data class OnOtherBranch(val currentBranch: String?, val featureBranch: String) : WorkGoalTimelineResult

  data class BaseUnavailable(val reason: String) : WorkGoalTimelineResult

  data class NotARepository(val worktreeRoot: String) : WorkGoalTimelineResult

  data class Failed(val message: String) : WorkGoalTimelineResult
}

sealed interface WorkGoalScopesResult {
  data class Listed(
    val worktreeRoot: String,
    val targetBranch: String?,
    val rangesSupported: Boolean,
    val subtasks: List<WorkScopeSubtask>,
    val issueKey: String,
    val featureBranch: String?,
    val committedHeadBranch: String? = null,
  ) : WorkGoalScopesResult

  data class NotAGoal(val workflowId: String) : WorkGoalScopesResult

  data class Unavailable(val reason: String) : WorkGoalScopesResult
}
