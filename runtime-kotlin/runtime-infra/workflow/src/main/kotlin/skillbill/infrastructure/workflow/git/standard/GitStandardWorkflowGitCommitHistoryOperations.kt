package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.WorkflowGitCommitHistoryOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitIdentity
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitIdentityResult
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object GitStandardWorkflowGitCommitHistoryOperations : WorkflowGitCommitHistoryOperations {
  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult = gitCreateCommit(repoRoot, message)

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "rev-parse", "HEAD")

  override fun commitIdentity(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitCommitIdentityResult {
    if (!commitSha.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) {
      return WorkflowGitCommitIdentityResult.Failed("A full commit SHA is required for commit identity lookup.")
    }
    val result = runGitCommand(repoRoot, "show", "-s", "--format=%T%n%P", commitSha)
    if (result !is WorkflowGitOperationResult.Ok) return WorkflowGitCommitIdentityResult.Failed(result.error)
    val lines = result.value.orEmpty().lines()
    val treeSha =
      lines.firstOrNull()?.takeIf(String::isNotBlank)
        ?: return WorkflowGitCommitIdentityResult.Failed("Git returned no tree identity for '$commitSha'.")
    return WorkflowGitCommitIdentityResult.Found(
      WorkflowGitCommitIdentity(treeSha, lines.getOrElse(1) { "" }.split(' ').filter(String::isNotBlank)),
    )
  }

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
