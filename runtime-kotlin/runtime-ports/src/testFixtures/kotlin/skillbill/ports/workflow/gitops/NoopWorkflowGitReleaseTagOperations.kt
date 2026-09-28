package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

object NoopWorkflowGitReleaseTagOperations : WorkflowGitReleaseTagOperations {
  override fun lastReleaseTag(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun commitLogSince(
    repoRoot: Path,
    sinceRef: String?,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun localBranchBehindRemote(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "false")

  override fun remoteBranchHead(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(
      value = WorkflowGitRemoteOperations.ABSENT_REMOTE_BRANCH,
    )

  override fun createAnnotatedTag(
    repoRoot: Path,
    tag: String,
    message: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(error = "This git operations implementation cannot create tag '$tag'.")

  override fun pushTag(
    repoRoot: Path,
    tag: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(error = "This git operations implementation cannot push tag '$tag'.")

  override fun deleteLocalTag(
    repoRoot: Path,
    tag: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(error = "This git operations implementation cannot delete tag '$tag'.")
}
