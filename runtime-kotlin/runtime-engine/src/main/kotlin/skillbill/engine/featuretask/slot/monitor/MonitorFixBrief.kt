package skillbill.engine.featuretask.slot.monitor

import skillbill.ports.idestatus.model.MonitorCiCheckBucket
import skillbill.ports.idestatus.model.MonitorCiCycleOutcome
import skillbill.ports.idestatus.model.MonitorCiCycleRecord

internal object MonitorFixBrief {
  fun conflictBaseBranch(latest: MonitorCiCycleRecord?): String? =
    latest?.takeIf { it.outcome == MonitorCiCycleOutcome.CONFLICTED }?.conflictBaseBranch?.takeIf(String::isNotBlank)

  fun stepContextFor(latest: MonitorCiCycleRecord?): String {
    conflictBaseBranch(latest)?.let { baseBranch ->
      return "## Merge conflict\n" +
        "The open pull request conflicts with '$baseBranch'. Run `git fetch origin $baseBranch`, then " +
        "`git merge --no-commit --no-ff origin/$baseBranch`. Resolve every conflict and stage the resolved files. " +
        "Leave the merge in progress: do not commit, conclude, or abort the merge, do not run `git reset`, and " +
        "do not push. The runtime commits the merge with both parents and pushes it. A repair " +
        "without a merge of 'origin/$baseBranch' in progress is not published."
    }
    if (latest == null || latest.outcome != MonitorCiCycleOutcome.FAILED) return NO_SNAPSHOT
    val failing = latest.checks.filter { it.bucket == MonitorCiCheckBucket.FAIL }
    if (failing.isEmpty()) return NO_SNAPSHOT
    return "## Failing CI checks\n" + failing.joinToString("\n") { check -> "- ${check.name}: ${check.link}" }
  }

  private const val NO_SNAPSHOT: String =
    "## Failing CI checks\n" +
      "No failing-check snapshot is recorded for this run. List the failing checks yourself with " +
      "`gh pr checks <pr-number> --json name,bucket,link`, then fix them."
}
