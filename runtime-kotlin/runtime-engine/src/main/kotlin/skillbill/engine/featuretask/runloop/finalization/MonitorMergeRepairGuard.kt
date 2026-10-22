package skillbill.engine.featuretask.runloop.finalization

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun WorkflowGitOperations.unmergedConflictBaseReason(
  repoRoot: Path,
  baseBranch: String,
): String? {
  val remoteBase = "origin/$baseBranch"
  val baseSha =
    resolveCommit(repoRoot, "refs/remotes/$remoteBase") as? WorkflowGitOperationResult.Ok
      ?: return "The merge-conflict repair cannot be published: '$remoteBase' does not resolve to a commit. " +
        "Fetch '$baseBranch' from origin and merge it before publishing."
  val head = headCommitSha(repoRoot) as? WorkflowGitOperationResult.Ok
  val mergeHead = resolveCommit(repoRoot, MERGE_HEAD) as? WorkflowGitOperationResult.Ok
  val merged =
    listOfNotNull(head, mergeHead).any { candidate -> reaches(repoRoot, baseSha.value, candidate.value) }
  return if (merged) {
    null
  } else {
    "The merge-conflict repair cannot be published: neither HEAD nor an in-progress merge contains " +
      "'$remoteBase' (${baseSha.value.trim()}). Publishing it would leave the pull request conflicting. Merge " +
      "'$remoteBase' with `git merge --no-commit --no-ff $remoteBase` and leave the merge in progress."
  }
}

private fun WorkflowGitOperations.reaches(
  repoRoot: Path,
  ancestor: String,
  descendant: String,
): Boolean {
  val result = isCommitAncestor(repoRoot, ancestor, descendant)
  return result is WorkflowGitOperationResult.Ok && result.value.trim() == "true"
}

private const val MERGE_HEAD = "MERGE_HEAD"
