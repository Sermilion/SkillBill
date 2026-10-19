package skillbill.ports.workflow.gitops.worktree

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import java.nio.file.Path

interface WorkflowGitWorktreeOperations {
  fun stageAll(repoRoot: Path): WorkflowGitOperationResult

  fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult

  /** Names the unfinished merge, rebase, cherry-pick, or revert in the worktree; empty when none is in progress. */
  fun inProgressOperation(repoRoot: Path): WorkflowGitOperationResult

  fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult

  fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult

  /** Per-file numstat of the worktree against HEAD plus untracked text files counted as insertions. */
  fun worktreeNumstat(repoRoot: Path): WorkflowWorktreeNumstatResult
}
