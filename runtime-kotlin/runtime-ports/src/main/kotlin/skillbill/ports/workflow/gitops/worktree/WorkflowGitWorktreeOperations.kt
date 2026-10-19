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

  /**
   * Resolves [commonDirRelativePath] under the Git common directory; the result value is the absolute, normalized
   * path. Creates nothing.
   */
  fun linkedWorktreePath(
    repoRoot: Path,
    commonDirRelativePath: String,
  ): WorkflowGitOperationResult

  /**
   * Checks [revision] out detached at [commonDirRelativePath] under the Git common directory, first removing a stale
   * worktree left at that path. The result value is the absolute worktree path.
   */
  fun addDetachedWorktree(
    repoRoot: Path,
    commonDirRelativePath: String,
    revision: String,
  ): WorkflowGitOperationResult

  /** Force-removes the linked worktree at [worktree] and prunes its registration; a missing path is a no-op. */
  fun removeWorktree(
    repoRoot: Path,
    worktree: Path,
  ): WorkflowGitOperationResult
}
