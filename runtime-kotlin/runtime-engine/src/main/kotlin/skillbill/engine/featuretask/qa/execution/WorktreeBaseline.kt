package skillbill.engine.featuretask.qa.execution

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

data class WorktreeBaseline(
  val status: String,
  val head: String,
)

inline fun WorkflowGitOperations.worktreeBaseline(
  repoRoot: Path,
  unreadable: (anchor: String, error: String) -> Nothing,
): WorktreeBaseline {
  val status =
    when (val result = worktreeStatus(repoRoot)) {
      is WorkflowGitOperationResult.Ok -> result.value.orEmpty()
      else -> unreadable(WORKTREE_BASELINE_STATUS_ANCHOR, result.error)
    }
  val head =
    when (val result = runtimePhaseHeadCommit(repoRoot)) {
      is WorkflowGitOperationResult.Ok -> result.value.trim()
      else -> unreadable(WORKTREE_BASELINE_HEAD_ANCHOR, result.error)
    }
  return WorktreeBaseline(status, head)
}

const val WORKTREE_BASELINE_STATUS_ANCHOR = "worktree status"
const val WORKTREE_BASELINE_HEAD_ANCHOR = "HEAD"
