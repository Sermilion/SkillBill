package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.runGitCommandPreservingOutput
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

  override fun readRegularFileAtCommit(
    repoRoot: Path,
    commitSha: String,
    repoRelativePath: String,
    maximumUtf8Bytes: Int,
  ): WorkflowGitOperationResult {
    val path = repoRelativePath.removePrefix("./")
    val segments = path.split('/')
    if (path.isBlank() || path.startsWith('/') || segments.any { it.isBlank() || it == "." || it == ".." } ||
      path.any { it == '\n' || it == '\r' || it == '\u0000' } || !commitSha.matches(Regex("[a-fA-F0-9]{40,64}")) ||
      maximumUtf8Bytes < 0
    ) {
      return WorkflowGitOperationResult.Failed(error = "A contained path, commit, and byte limit are required.")
    }
    val entry = runGitCommand(repoRoot, "ls-tree", commitSha, "--", path)
    if (entry !is WorkflowGitOperationResult.Ok) return entry
    val record = entry.value.lineSequence().singleOrNull { it.endsWith("\t$path") }
      ?: return WorkflowGitOperationResult.Failed(error = "The checkpoint path is missing or ambiguous.")
    if (!record.startsWith("100644 blob ") && !record.startsWith("100755 blob ")) {
      return WorkflowGitOperationResult.Failed(error = "The checkpoint path is not a regular file.")
    }
    val size = runGitCommand(repoRoot, "cat-file", "-s", "$commitSha:$path")
    val byteCount = (size as? WorkflowGitOperationResult.Ok)?.value?.trim()?.toLongOrNull()
      ?: return WorkflowGitOperationResult.Failed(error = "The checkpoint file size is unavailable.")
    if (byteCount > maximumUtf8Bytes) {
      return WorkflowGitOperationResult.Failed(error = "The checkpoint file exceeds its UTF-8 byte limit.")
    }
    val contents = runGitCommandPreservingOutput(repoRoot, "show", "$commitSha:$path")
    if (contents !is WorkflowGitOperationResult.Ok) return contents
    if (contents.value.toByteArray(Charsets.UTF_8).size > maximumUtf8Bytes) {
      return WorkflowGitOperationResult.Failed(error = "The checkpoint file exceeds its UTF-8 byte limit.")
    }
    return contents
  }
}
