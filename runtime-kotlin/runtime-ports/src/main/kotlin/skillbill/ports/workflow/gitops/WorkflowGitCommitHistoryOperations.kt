package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface WorkflowGitCommitHistoryOperations {
  fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult

  fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult

  fun commitCountAhead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult

  fun mergeBaseWithHead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult

  fun resetSoftToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult

  fun resetHardToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult

  fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult

  fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult

  fun readHeadTrackedFile(
    repoRoot: Path,
    repoRelativePath: String,
  ): WorkflowGitOperationResult

  fun readRegularFileAtCommit(
    repoRoot: Path,
    commitSha: String,
    repoRelativePath: String,
    maximumUtf8Bytes: Int,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(error = "Historical file reads are not supported.")
}
