package skillbill.infrastructure.workflow.git.changes

import skillbill.infrastructure.workflow.git.standard.gitDefaultBranch
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workchanges.model.WorkTreeComparisonResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun integrateSnapshotUpstream(
  root: Path,
  headSha: String,
  snapshotSha: String?,
): WorkTreeComparisonResult {
  val baseline = snapshotSha ?: headSha
  repositoryProblem(
    root,
    WorkTreeComparisonResult::Unavailable,
    WorkTreeComparisonResult::Unavailable,
  )?.let { return it }
  when (val missing = missingCommit(root, listOfNotNull(headSha, snapshotSha))) {
    is BaseRead.Unavailable -> return WorkTreeComparisonResult.Unavailable(missing.reason)
    is BaseRead.Failed -> return WorkTreeComparisonResult.Unavailable(missing.message)
    else -> Unit
  }
  return when (val upstream = integratedUpstream(root)) {
    null -> WorkTreeComparisonResult.Resolved(baseline, null)
    is BaseRead.Unavailable -> WorkTreeComparisonResult.Unavailable(upstream.reason)
    is BaseRead.Failed -> WorkTreeComparisonResult.Unavailable(upstream.message)
    is BaseRead.Resolved -> mergeSnapshot(root, baseline, upstream.sha)
  }
}

private fun integratedUpstream(root: Path): BaseRead? {
  var failure: String? = null
  val refs =
    captureList(root, listOf("for-each-ref", "--format=%(refname)", "refs/remotes"), { message ->
      failure = message
      emptyList()
    }) { text -> text.lineSequence().filter(String::isNotBlank).toList() }
  failure?.let { return BaseRead.Failed(it) }
  if (refs.isEmpty()) return null
  return when (val result = gitDefaultBranch(root)) {
    is WorkflowGitOperationResult.Failed -> BaseRead.Unavailable(result.error)
    is WorkflowGitOperationResult.Ok -> {
      val candidates = refs.filter { it.endsWith("/${result.value}") }
      val remote = candidates.firstOrNull { it == "refs/remotes/origin/${result.value}" } ?: candidates.firstOrNull()
      if (remote == null) {
        BaseRead.Unavailable(
          "No remote default branch for ${result.value}",
        )
      } else {
        mergeBase(root, remote)
      }
    }
  }
}

private fun mergeSnapshot(
  root: Path,
  baseline: String,
  upstream: String,
): WorkTreeComparisonResult {
  return when (val common = mergeBase(root, baseline, upstream)) {
    is BaseRead.Resolved ->
      when (common.sha) {
        upstream -> WorkTreeComparisonResult.Resolved(baseline, upstream)
        baseline -> WorkTreeComparisonResult.Resolved(upstream, upstream)
        else -> mergeSnapshotTree(root, baseline, upstream)
      }
    is BaseRead.Unavailable -> WorkTreeComparisonResult.Unavailable(common.reason)
    is BaseRead.Failed -> WorkTreeComparisonResult.Unavailable(common.message)
  }
}

private fun mergeSnapshotTree(
  root: Path,
  baseline: String,
  upstream: String,
): WorkTreeComparisonResult {
  val args = listOf("merge-tree", "--write-tree", "--no-messages", baseline, upstream)
  val tree =
    when (val result = runGitCommand(root, args)) {
      is WorkflowGitOperationResult.Ok -> result.value
      is WorkflowGitOperationResult.Failed -> return WorkTreeComparisonResult.Unavailable(result.error)
    }
  val identity = listOf("-c", "user.name=Skill Bill", "-c", "user.email=baseline@skill-bill.test")
  val dates = mapOf("GIT_AUTHOR_DATE" to "2000-01-01T00:00:00Z", "GIT_COMMITTER_DATE" to "2000-01-01T00:00:00Z")
  return when (
    val result =
      runGitCommand(
        root,
        dates,
        identity + listOf("commit-tree", tree, "-p", baseline, "-p", upstream, "-m", "Chat comparison baseline"),
      )
  ) {
    is WorkflowGitOperationResult.Ok -> WorkTreeComparisonResult.Resolved(result.value, upstream)
    is WorkflowGitOperationResult.Failed -> WorkTreeComparisonResult.Unavailable(result.error)
  }
}
