package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.scoped.GIT_NUL
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.runGitCommandWithStdin
import skillbill.infrastructure.workflow.process.runGitForActivity
import skillbill.infrastructure.workflow.process.withValue
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun gitCheckoutBranch(
  repoRoot: Path,
  branch: String,
  baseBranch: String?,
): WorkflowGitOperationResult {
  val normalizedBranch = branch.trim()
  if (normalizedBranch.isBlank()) {
    return WorkflowGitOperationResult.Failed(error = "Branch name is required.")
  }
  val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", normalizedBranch)
  return if (existing is WorkflowGitOperationResult.Ok) {
    gitCheckoutPreservingLocalChanges(repoRoot, normalizedBranch, listOf("checkout", "--merge", normalizedBranch))
      .withValue(normalizedBranch)
  } else {
    val base = baseBranch?.trim().orEmpty()
    if (base.isBlank()) {
      gitCheckoutPreservingLocalChanges(repoRoot, null, listOf("checkout", "--merge", "-b", normalizedBranch))
        .withValue(normalizedBranch)
    } else {
      gitCheckoutPreservingLocalChanges(repoRoot, base, listOf("checkout", "--merge", "-b", normalizedBranch, base))
        .withValue(normalizedBranch)
    }
  }
}

internal fun gitCheckoutPreservingLocalChanges(
  repoRoot: Path,
  targetRef: String?,
  args: List<String>,
): WorkflowGitOperationResult {
  val existingConflictMarkers = gitConflictMarkerPaths(repoRoot)
  val previouslyStaged = gitStagedPaths(repoRoot)
  if (previouslyStaged.isNotEmpty()) {
    val cleared = runGitCommand(repoRoot, "reset", "--quiet")
    if (cleared !is WorkflowGitOperationResult.Ok) return cleared
  }
  val outcome =
    gitCheckoutCarryingUntracked(repoRoot, targetRef) { gitMergeCheckout(repoRoot, args, existingConflictMarkers) }
  if (previouslyStaged.isEmpty()) return outcome
  val stdin = previouslyStaged.joinToString(separator = GIT_NUL.toString(), postfix = GIT_NUL.toString()).toByteArray()
  val restaged = runGitCommandWithStdin(repoRoot, listOf("update-index", "--add", "--remove", "-z", "--stdin"), stdin)
  return if (restaged is WorkflowGitOperationResult.Ok) outcome else restaged
}

private fun gitMergeCheckout(
  repoRoot: Path,
  args: List<String>,
  existingConflictMarkers: List<String>,
): WorkflowGitOperationResult {
  val checkout = runGitCommand(repoRoot, args)
  val paths = gitConflictMarkerPaths(repoRoot).filterNot(existingConflictMarkers::contains)
  if (paths.isEmpty()) return checkout
  val resolved = runGitCommand(repoRoot, listOf("checkout", "--theirs", "--") + paths)
  if (resolved !is WorkflowGitOperationResult.Ok) return checkout
  val staged = runGitCommand(repoRoot, listOf("add", "--all", "--") + paths)
  return if (staged is WorkflowGitOperationResult.Ok) {
    WorkflowGitOperationResult.Ok(value = checkout.value)
  } else {
    staged
  }
}

internal fun gitStagedPaths(repoRoot: Path): List<String> {
  val staged = runGitCommand(repoRoot, "diff", "--cached", "--name-only", "-z", "HEAD")
  if (staged !is WorkflowGitOperationResult.Ok) return emptyList()
  return staged.value.split('\u0000').filter(String::isNotBlank)
}

internal fun gitConflictMarkerPaths(repoRoot: Path): List<String> {
  val check = runGitForActivity(repoRoot, listOf("diff", "--check"))
  if (check is WorkflowGitOperationResult.Ok) return emptyList()
  val markerPattern = Regex("""^(.*):\d+: leftover conflict marker$""")
  return check.error.lineSequence()
    .mapNotNull { line -> markerPattern.matchEntire(line)?.groupValues?.get(1) }
    .distinct()
    .toList()
}
