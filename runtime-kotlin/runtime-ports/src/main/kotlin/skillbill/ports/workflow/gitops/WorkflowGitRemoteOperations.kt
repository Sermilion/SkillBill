package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface WorkflowGitRemoteOperations {
  companion object {
    const val ABSENT_REMOTE_BRANCH = "absent"
  }

  fun pushBranch(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult

  /**
   * Pushes a branch the runtime owns, such as a goal's PR branch. A rejected push first rebases onto the
   * remote commits, as [pushBranch] does; when that rebase fails, it replaces the remote branch with a
   * lease on the remote head it just read.
   */
  fun pushOwnedBranch(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = pushBranch(repoRoot, branch)

  fun pushBranchWithLease(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult

  fun refreshRemoteBranch(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult

  fun localBranchHasUnpushedCommits(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult
}
