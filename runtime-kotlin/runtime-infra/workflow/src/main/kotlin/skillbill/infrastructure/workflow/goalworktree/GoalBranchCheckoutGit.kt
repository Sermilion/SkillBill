package skillbill.infrastructure.workflow.goalworktree

import skillbill.infrastructure.workflow.worktree.worktreeGit
import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val MAX_DETAIL_LENGTH = 300
private const val MAX_LISTED_PATHS = 5
private const val STATUS_PREFIX_LENGTH = 3

private val IN_PROGRESS_OPERATIONS =
  listOf(
    "MERGE_HEAD" to "merge",
    "rebase-merge" to "rebase",
    "rebase-apply" to "rebase or am",
    "CHERRY_PICK_HEAD" to "cherry-pick",
    "REVERT_HEAD" to "revert",
  )

internal fun checkoutGoalBranchAt(
  primaryRoot: Path,
  branch: String,
): GoalBranchCheckout =
  try {
    if (branch.isBlank() || branch.startsWith("-")) {
      failed("invalid goal branch '$branch'")
    } else {
      switchCleanCheckout(primaryRoot, branch)
    }
  } catch (failure: IOException) {
    failed("git checkout failed: ${failure.message}")
  } catch (failure: SecurityException) {
    failed("git checkout failed: ${failure.message}")
  }

private fun switchCleanCheckout(
  root: Path,
  branch: String,
): GoalBranchCheckout {
  val status = worktreeGit(root, "status", "--porcelain", "--untracked-files=no")
  val changed = status.output.lines().filter(String::isNotBlank)
  return when {
    !status.ok -> failed("git status failed: ${status.output}")
    changed.isNotEmpty() -> skipped(changed)
    else -> operationInProgress(root)?.let(::failed) ?: switchBranch(root, branch)
  }
}

private fun operationInProgress(root: Path): String? {
  for ((name, label) in IN_PROGRESS_OPERATIONS) {
    val located = worktreeGit(root, "rev-parse", "--git-path", name)
    if (!located.ok) return "git rev-parse --git-path $name failed: ${located.output}"
    if (Files.exists(root.resolve(located.output.trim()), LinkOption.NOFOLLOW_LINKS)) {
      return "a $label is in progress in the main checkout"
    }
  }
  return null
}

private fun switchBranch(
  root: Path,
  branch: String,
): GoalBranchCheckout {
  val current = worktreeGit(root, "symbolic-ref", "--short", "-q", "HEAD")
  return when {
    current.ok && current.output.trim() == branch -> GoalBranchCheckout.CheckedOut
    !worktreeGit(root, "rev-parse", "--verify", "--quiet", "refs/heads/$branch").ok ->
      failed("branch $branch does not exist locally")
    else -> {
      val checkout = worktreeGit(root, "checkout", branch, "--")
      if (checkout.ok) GoalBranchCheckout.CheckedOut else failed("git checkout failed: ${checkout.output}")
    }
  }
}

private fun skipped(changed: List<String>): GoalBranchCheckout.SkippedUncommittedChanges {
  val paths = changed.take(MAX_LISTED_PATHS).joinToString(", ") { it.drop(STATUS_PREFIX_LENGTH) }
  val more = if (changed.size > MAX_LISTED_PATHS) ", ..." else ""
  return GoalBranchCheckout.SkippedUncommittedChanges(
    boundedDetail("the main checkout has ${changed.size} uncommitted change(s): $paths$more"),
  )
}

private fun failed(detail: String): GoalBranchCheckout.Failed = GoalBranchCheckout.Failed(boundedDetail(detail))

private fun boundedDetail(text: String): String = text.trim().ifBlank { "git checkout failed" }.take(MAX_DETAIL_LENGTH)
