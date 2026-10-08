package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopWorkflowGitBranchOperations : WorkflowGitBranchOperations {
  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = branch)
  }

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = "false")
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = "")
  }

  override fun defaultBranch(repoRoot: Path): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = "")
  }

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = expectedBaseBranch)
  }
}
