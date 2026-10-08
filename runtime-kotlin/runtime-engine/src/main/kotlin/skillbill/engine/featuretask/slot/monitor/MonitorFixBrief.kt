package skillbill.engine.featuretask.slot.monitor

import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import java.util.concurrent.ConcurrentHashMap

internal class MonitorFixBrief {
  private val failingChecks = ConcurrentHashMap<String, List<PullRequestCheck>>()

  fun record(
    issueKey: String,
    checks: List<PullRequestCheck>,
  ) {
    failingChecks[issueKey] = checks
  }

  fun stepContextFor(issueKey: String): String {
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
