package skillbill.infrastructure.workflow.git.standard

import skillbill.ports.workflow.gitops.WorkflowGitRemoteOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object GitStandardWorkflowGitRemoteOperations : WorkflowGitRemoteOperations {
  override fun pushBranch(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = gitPushBranch(repoRoot, branch, withLease = false)

  override fun pushOwnedBranch(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = gitPushBranch(repoRoot, branch, withLease = false, owned = true)

  override fun pushBranchWithLease(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = gitPushBranch(repoRoot, branch, withLease = true)

  override fun refreshRemoteBranch(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = gitFetchRemoteBranch(repoRoot, branch)

  override fun localBranchHasUnpushedCommits(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = gitLocalBranchHasUnpushedCommits(repoRoot, branch)
}
