package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.runGitCommandWithStdin
import skillbill.ports.workflow.gitops.WorkflowGitReleaseTagOperations
import skillbill.ports.workflow.gitops.WorkflowGitRemoteOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object GitStandardWorkflowGitReleaseTagOperations : WorkflowGitReleaseTagOperations {
  private val STABLE_RELEASE_TAG = Regex("""^v\d+\.\d+\.\d+$""")

  override fun lastReleaseTag(repoRoot: Path): WorkflowGitOperationResult {
    // All tags, as the release skill read them: a newer tag on another branch must not be proposed again.
    val tags = runGitCommand(repoRoot, "tag", "--sort=-version:refname", "--list", "v*")
    if (tags !is WorkflowGitOperationResult.Ok) return tags
    val latest = tags.value.lines().map(String::trim).firstOrNull(STABLE_RELEASE_TAG::matches)
    return WorkflowGitOperationResult.Ok(value = latest.orEmpty())
  }

  override fun commitLogSince(
    repoRoot: Path,
    sinceRef: String?,
  ): WorkflowGitOperationResult {
    val range = sinceRef?.trim()?.takeIf(String::isNotBlank)?.let { ref -> "$ref..HEAD" } ?: "HEAD"
    return runGitCommand(repoRoot, "log", "--no-decorate", "--format=%h %s", range)
  }

  override fun localBranchBehindRemote(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult {
    val remoteRef = "refs/remotes/origin/${branch.trim()}"
    if (runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", remoteRef) !is WorkflowGitOperationResult.Ok) {
      return WorkflowGitOperationResult.Ok(value = "false")
    }
    val behind = runGitCommand(repoRoot, "rev-list", "--count", "HEAD..$remoteRef")
    if (behind !is WorkflowGitOperationResult.Ok) return behind
    val count =
      behind.value.trim().toIntOrNull()
        ?: return WorkflowGitOperationResult.Failed(error = "Could not parse behind count '${behind.value.trim()}'.")
    return WorkflowGitOperationResult.Ok(value = (count > 0).toString())
  }

  override fun remoteBranchHead(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult {
    val head = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/${branch.trim()}")
    return if (head is WorkflowGitOperationResult.Ok && head.value.isNotBlank()) {
      head
    } else {
      WorkflowGitOperationResult.Ok(value = WorkflowGitRemoteOperations.ABSENT_REMOTE_BRANCH)
    }
  }

  override fun createAnnotatedTag(
    repoRoot: Path,
    tag: String,
    message: String,
  ): WorkflowGitOperationResult =
    runGitCommandWithStdin(
      repoRoot,
      listOf("tag", "--annotate", "--cleanup=verbatim", "--file=-", tag.trim()),
      message.toByteArray(Charsets.UTF_8),
    ).let { result -> if (result is WorkflowGitOperationResult.Ok) result.copy(value = tag.trim()) else result }

  override fun pushTag(
    repoRoot: Path,
    tag: String,
  ): WorkflowGitOperationResult = runGitCommand(repoRoot, "push", "origin", "refs/tags/${tag.trim()}")

  override fun deleteLocalTag(
    repoRoot: Path,
    tag: String,
  ): WorkflowGitOperationResult = runGitCommand(repoRoot, "tag", "--delete", tag.trim())
}
