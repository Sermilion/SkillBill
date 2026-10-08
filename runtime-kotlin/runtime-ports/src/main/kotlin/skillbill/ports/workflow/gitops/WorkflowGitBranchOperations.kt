package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface WorkflowGitBranchOperations : DefaultBranchGitOperations {
  fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String? = null,
  ): WorkflowGitOperationResult

  fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult

  fun currentBranch(repoRoot: Path): WorkflowGitOperationResult

  fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult
}

interface DefaultBranchGitOperations {
  /** The branch the repository integrates into: `origin/HEAD`, else the first of main, master, trunk that exists. */
  fun defaultBranch(repoRoot: Path): WorkflowGitOperationResult
}
