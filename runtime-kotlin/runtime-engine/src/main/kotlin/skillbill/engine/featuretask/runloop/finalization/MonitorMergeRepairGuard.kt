package skillbill.engine.featuretask.runloop.finalization

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

/**
 * Returns why a merge-conflict repair cannot be published, or null when it can.
 *
 * A repair is publishable when HEAD already contains `origin/<base>`, or when an in-progress merge brings
 * base commits HEAD lacks. The base can move while the agent resolves conflicts, so `MERGE_HEAD` may be an
 * older base commit than the current `origin/<base>`. Publishing that merge keeps the base as a parent and
 * makes progress; if the pull request still conflicts, the next monitor round merges the newer tip.
 */
internal fun WorkflowGitOperations.unmergedConflictBaseReason(
  repoRoot: Path,
  baseBranch: String,
): String? {
  val remoteBase = "origin/$baseBranch"
  val baseSha =
    (resolveCommit(repoRoot, "refs/remotes/$remoteBase") as? WorkflowGitOperationResult.Ok)?.value?.trim()
      ?: return "The merge-conflict repair cannot be published: '$remoteBase' does not resolve to a commit. " +
        "Fetch '$baseBranch' from origin and merge it before publishing."
  val head = (headCommitSha(repoRoot) as? WorkflowGitOperationResult.Ok)?.value?.trim()
  val mergeHead = (resolveCommit(repoRoot, MERGE_HEAD) as? WorkflowGitOperationResult.Ok)?.value?.trim()
  val merged =
    head != null && reaches(repoRoot, baseSha, head) ||
      mergeHead != null && mergesBaseProgress(repoRoot, baseSha, mergeHead, head)
  return if (merged) {
    null
  } else {
    "The merge-conflict repair cannot be published: neither HEAD nor an in-progress merge contains " +
      "'$remoteBase' ($baseSha). Publishing it would leave the pull request conflicting. Merge " +
      "'$remoteBase' with `git merge --no-commit --no-ff $remoteBase` and leave the merge in progress."
  }
}

private fun WorkflowGitOperations.mergesBaseProgress(
  repoRoot: Path,
  baseSha: String,
  mergeHead: String,
  head: String?,
): Boolean {
  if (reaches(repoRoot, baseSha, mergeHead)) return true
  val isBaseCommit = reaches(repoRoot, mergeHead, baseSha)
  val alreadyInHead = head != null && reaches(repoRoot, mergeHead, head)
  return isBaseCommit && !alreadyInHead
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
