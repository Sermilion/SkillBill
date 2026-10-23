package skillbill.ports.workflow.gitops.worktree

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowIgnoredFilesCopyResult
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
   * Checks [revision] out detached at the absolute [worktree] path, first removing a stale worktree left there. The
   * result value is the absolute worktree path.
   */
  fun addDetachedWorktree(
    repoRoot: Path,
    worktree: Path,
    revision: String,
  ): WorkflowGitOperationResult

  /**
   * Gives the linked [worktree], checked out at the HEAD of [repoRoot], the same staged, unstaged, and untracked
   * state as [repoRoot]. Fails when the copy does not report the same repository tree identity.
   */
  fun copyWorkingState(
    repoRoot: Path,
    worktree: Path,
  ): WorkflowGitOperationResult

  /**
   * Copies the ignored files of [repoRoot] that sit outside ignored directories into the same relative paths of
   * [worktree]. A file larger than [maxFileBytes], or one that is neither a regular file nor a symbolic link, is
   * skipped and named in the result.
   */
  fun copyIgnoredFiles(
    repoRoot: Path,
    worktree: Path,
    maxFileBytes: Long,
  ): WorkflowIgnoredFilesCopyResult

  /** Force-removes the linked worktree at [worktree] and prunes its registration; a missing path is a no-op. */
  fun removeWorktree(
    repoRoot: Path,
    worktree: Path,
  ): WorkflowGitOperationResult
}
