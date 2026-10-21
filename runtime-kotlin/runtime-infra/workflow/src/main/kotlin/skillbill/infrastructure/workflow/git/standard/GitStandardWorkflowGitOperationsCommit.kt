package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.gitTimedOutError
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.infrastructure.workflow.process.withValue
import skillbill.ports.workflow.gitops.WorkflowGitRemoteOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun gitBranchExists(
  repoRoot: Path,
  branch: String,
): WorkflowGitOperationResult {
  val normalizedBranch = branch.trim()
  if (normalizedBranch.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "Branch name is required.")
  }
  val args = listOf("rev-parse", "--verify", "--quiet", "refs/heads/$normalizedBranch")
  val existing = runGitProcess(repoRoot, args)
  return when {
    existing.timedOut ->
      WorkflowGitOperationResult.Failed(
        error = gitTimedOutError(args),
      )
    existing.readFailure != null ->
      WorkflowGitOperationResult.Failed(
        error = existing.readFailure.message.orEmpty(),
      )
    existing.exitCode == 0 -> WorkflowGitOperationResult.Ok(value = "true")
    existing.exitCode == 1 -> WorkflowGitOperationResult.Ok(value = "false")
    else ->
      WorkflowGitOperationResult.Failed(
        error = "git ${args.joinToString(" ")} failed with exit code ${existing.exitCode}: ${existing.output}",
      )
  }
}

internal fun gitCreateCommit(
  repoRoot: Path,
  message: String,
): WorkflowGitCommitResult {
  val commit = runGitCommand(repoRoot, "commit", "-m", message)
  return when {
    commit is WorkflowGitOperationResult.Ok -> {
      val head = runGitCommand(repoRoot, "rev-parse", "HEAD")
      if (head is WorkflowGitOperationResult.Ok) {
        WorkflowGitCommitResult.Committed(head.value)
      } else {
        WorkflowGitCommitResult.Failed(head.error)
      }
    }
    commit.reportsGitNothingStaged() -> WorkflowGitCommitResult.NothingToCommit
    else -> WorkflowGitCommitResult.Failed(commit.error)
  }
}

private fun WorkflowGitOperationResult.reportsGitNothingStaged(): Boolean {
  val text = "$error $value"
  return NOTHING_TO_COMMIT_MARKERS.any { marker -> marker in text }
}

private val NOTHING_TO_COMMIT_MARKERS =
  listOf(
    "no changes added to commit",
    "nothing to commit",
    "nothing added to commit",
  )

internal fun gitFetchRemoteBranch(
  repoRoot: Path,
  branch: String,
): WorkflowGitOperationResult {
  val normalized = branch.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "Branch name is required to fetch.")
  }
  val remoteTracking = "refs/remotes/origin/$normalized"
  val refspec = "+refs/heads/$normalized:$remoteTracking"
  val fetched = runGitCommand(repoRoot, "fetch", "origin", refspec)
  if (fetched is WorkflowGitOperationResult.Ok) return fetched.withValue(normalized)
  if (!remoteRefMissing(fetched)) return fetched
  runGitCommand(repoRoot, "update-ref", "-d", remoteTracking)
  return WorkflowGitOperationResult.Ok(value = WorkflowGitRemoteOperations.ABSENT_REMOTE_BRANCH)
}

private fun remoteRefMissing(result: WorkflowGitOperationResult): Boolean {
  val text = "${result.error} ${result.value}"
  return "couldn't find remote ref" in text.lowercase()
}

internal fun gitLocalBranchHasUnpushedCommits(
  repoRoot: Path,
  branch: String,
): WorkflowGitOperationResult {
  val normalized = branch.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "Branch name is required to compare with origin.")
  }
  val remoteRef = "origin/$normalized"
  val remote = runGitCommand(repoRoot, "rev-parse", "--verify", remoteRef)
  if (remote !is WorkflowGitOperationResult.Ok) {
    val published =
      runGitCommand(
        repoRoot,
        "for-each-ref",
        "--contains",
        normalized,
        "--format=%(refname)",
        "refs/remotes/origin",
      )
    if (published !is WorkflowGitOperationResult.Ok) {
      return WorkflowGitOperationResult.Failed(
        error = "Could not inspect remote refs for local '$normalized': ${published.error}",
      )
    }
    return WorkflowGitOperationResult.Ok(value = if (published.value.isBlank()) "true" else "false")
  }
  val ahead = runGitCommand(repoRoot, "rev-list", "--count", "$remoteRef..$normalized")
  val count = ahead.value.trim().toIntOrNull()
  return when {
    ahead !is WorkflowGitOperationResult.Ok ->
      WorkflowGitOperationResult.Failed(
        error = "Could not compare local '$normalized' to '$remoteRef': ${ahead.error}",
      )
    count == null ->
      WorkflowGitOperationResult.Failed(
        error = "Could not parse unpushed commit count for '$normalized': '${ahead.value.trim()}'.",
      )
    else -> WorkflowGitOperationResult.Ok(value = if (count > 0) "true" else "false")
  }
}

internal fun gitResetSoftToCommit(
  repoRoot: Path,
  commitSha: String,
): WorkflowGitOperationResult {
  val normalized = commitSha.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "A commit SHA is required to soft-reset HEAD.")
  }
  return runGitCommand(repoRoot, "reset", "--soft", normalized)
}

internal fun gitResetHardToCommit(
  repoRoot: Path,
  commitSha: String,
): WorkflowGitOperationResult {
  val normalized = commitSha.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "A commit SHA is required to hard-reset HEAD.")
  }
  return runGitCommand(repoRoot, "reset", "--hard", normalized)
}

internal fun gitIsCommitAncestor(
  repoRoot: Path,
  ancestorSha: String,
  descendantSha: String,
): WorkflowGitOperationResult {
  val ancestor = ancestorSha.trim()
  val descendant = descendantSha.trim()
  if (ancestor.isBlank() || descendant.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "Ancestor and descendant commit SHAs are required.")
  }
  if (ancestor == descendant) {
    return WorkflowGitOperationResult.Ok(value = "true")
  }
  val args = listOf("merge-base", "--is-ancestor", ancestor, descendant)
  val result = runGitProcess(repoRoot, args)
  return when {
    result.timedOut ->
      WorkflowGitOperationResult.Failed(
        error = gitTimedOutError(args),
      )
    result.readFailure != null ->
      WorkflowGitOperationResult.Failed(
        error = result.readFailure.message.orEmpty(),
      )
    result.exitCode == 0 -> WorkflowGitOperationResult.Ok(value = "true")
    result.exitCode == 1 -> WorkflowGitOperationResult.Ok(value = "false")
    else ->
      WorkflowGitOperationResult.Failed(
        error = "git ${args.joinToString(" ")} failed with exit code ${result.exitCode}: ${result.output}",
      )
  }
}

internal fun gitResolveCommit(
  repoRoot: Path,
  revision: String,
): WorkflowGitOperationResult {
  val normalized = revision.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "A commit revision is required.")
  }
  val resolved = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", "$normalized^{commit}")
  return if (resolved is WorkflowGitOperationResult.Ok && !resolved.value.isNullOrBlank()) {
    resolved
  } else {
    WorkflowGitOperationResult.Failed(
      error = "Revision '$normalized' does not name a commit in this repository.",
    )
  }
}

internal fun gitValidateBranchBase(
  repoRoot: Path,
  branch: String,
  expectedBaseBranch: String,
): WorkflowGitOperationResult {
  val normalizedBranch = branch.trim()
  val normalizedBase = expectedBaseBranch.trim()
  if (normalizedBranch.isBlank() || normalizedBase.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "Branch and expected base branch are required.")
  }
  val result = runGitCommand(repoRoot, "merge-base", "--is-ancestor", normalizedBase, normalizedBranch)
  return if (result is WorkflowGitOperationResult.Ok) {
    WorkflowGitOperationResult.Ok(value = normalizedBase)
  } else {
    WorkflowGitOperationResult.Failed(
      error = "Branch '$normalizedBranch' is not based on '$normalizedBase'. ${result.error}".trim(),
    )
  }
}
