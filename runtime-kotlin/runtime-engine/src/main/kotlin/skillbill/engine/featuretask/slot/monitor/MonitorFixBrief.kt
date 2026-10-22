package skillbill.engine.featuretask.slot.monitor

import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import java.util.concurrent.ConcurrentHashMap

internal class MonitorFixBrief {
  private val failingChecks = ConcurrentHashMap<String, List<PullRequestCheck>>()
  private val conflictBaseBranches = ConcurrentHashMap<String, String>()

  fun record(
    issueKey: String,
    checks: List<PullRequestCheck>,
  ) {
    conflictBaseBranches.remove(issueKey)
    failingChecks[issueKey] = checks
  }

  fun recordConflict(
    issueKey: String,
    baseBranch: String,
  ) {
    failingChecks.remove(issueKey)
    conflictBaseBranches[issueKey] = baseBranch
  }

  fun conflictBaseBranch(issueKey: String): String? = conflictBaseBranches[issueKey]

  fun stepContextFor(issueKey: String): String {
    conflictBaseBranches[issueKey]?.let { baseBranch ->
      return "## Merge conflict\n" +
        "The open pull request conflicts with '$baseBranch'. Run `git fetch origin $baseBranch`, then " +
        "`git merge --no-commit --no-ff origin/$baseBranch`. Resolve every conflict and stage the resolved files. " +
        "Leave the merge in progress: do not commit, conclude, or abort the merge, do not run `git reset`, and " +
        "do not push. The runtime commits the merge with both parents and pushes it. A repair " +
        "without a merge of 'origin/$baseBranch' in progress is not published."
    }
    val checks = failingChecks[issueKey] ?: return NO_SNAPSHOT
    return "## Failing CI checks\n" + checks.joinToString("\n") { check -> "- ${check.name}: ${check.link}" }
  }

  private companion object {
    const val NO_SNAPSHOT: String =
      "## Failing CI checks\n" +
        "No failing-check snapshot is recorded for this run. List the failing checks yourself with " +
        "`gh pr checks <pr-number> --json name,bucket,link`, then fix them."
  }
}
