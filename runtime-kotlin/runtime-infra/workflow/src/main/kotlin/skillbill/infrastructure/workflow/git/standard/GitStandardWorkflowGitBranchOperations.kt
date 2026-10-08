package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.WorkflowGitBranchOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object GitStandardWorkflowGitBranchOperations : WorkflowGitBranchOperations {
  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult = gitCheckoutBranch(repoRoot, branch, baseBranch)

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = gitBranchExists(repoRoot, branch)

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "branch", "--show-current")

  override fun defaultBranch(repoRoot: Path): WorkflowGitOperationResult = gitDefaultBranch(repoRoot)

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = gitValidateBranchBase(repoRoot, branch, expectedBaseBranch)
}
