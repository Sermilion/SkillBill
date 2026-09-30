package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopWorkflowGitCommitHistoryOperations : WorkflowGitCommitHistoryOperations {
  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult {
    return WorkflowGitCommitResult.Committed(
      commitSha = "recorded:${message.hashCode().toUInt().toString(HASH_RADIX_HEX)}",
    )
  }

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = "")
  }

  override fun commitCountAhead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot count commits ahead of '$baseRevision'.",
    )

  override fun mergeBaseWithHead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot find the merge base of '$baseRevision' and HEAD.",
    )

  override fun resetSoftToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = commitSha.trim())
  }

  override fun resetHardToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = commitSha.trim())
  }

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(
      value = if (ancestorSha.trim() == descendantSha.trim()) "true" else "true",
    )
  }

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot resolve commit '$revision'.",
    )

  override fun readHeadTrackedFile(
    repoRoot: Path,
    repoRelativePath: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot read tracked file '$repoRelativePath' at HEAD.",
    )
}
