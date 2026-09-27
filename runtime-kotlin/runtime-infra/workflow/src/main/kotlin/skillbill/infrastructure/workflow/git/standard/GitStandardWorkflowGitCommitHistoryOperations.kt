package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.WorkflowGitCommitHistoryOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object GitStandardWorkflowGitCommitHistoryOperations : WorkflowGitCommitHistoryOperations {
  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult = gitCreateCommit(repoRoot, message)

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "rev-parse", "HEAD")

  override fun commitCountAhead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult = runGitCommand(repoRoot, "rev-list", "--count", "$baseRevision..HEAD")

  override fun mergeBaseWithHead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult = runGitCommand(repoRoot, "merge-base", baseRevision, "HEAD")

  override fun resetSoftToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult = gitResetSoftToCommit(repoRoot, commitSha)

  override fun resetHardToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult = gitResetHardToCommit(repoRoot, commitSha)

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult = gitIsCommitAncestor(repoRoot, ancestorSha, descendantSha)

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult = gitResolveCommit(repoRoot, revision)

  override fun readHeadTrackedFile(
    repoRoot: Path,
    repoRelativePath: String,
  ): WorkflowGitOperationResult {
    val path = repoRelativePath.trim().removePrefix("./")
    if (path.isBlank()) {
      return WorkflowGitOperationResult.Failed(error = "A repository-relative path is required.")
    }
    val tracked = runGitCommand(repoRoot, "ls-files", "--error-unmatch", path)
    if (tracked !is WorkflowGitOperationResult.Ok) {
      return WorkflowGitOperationResult.Failed(
        error = "Path '$path' is not tracked at HEAD (${tracked.error}).",
      )
    }
    return runGitCommand(repoRoot, "show", "HEAD:$path")
  }
}
