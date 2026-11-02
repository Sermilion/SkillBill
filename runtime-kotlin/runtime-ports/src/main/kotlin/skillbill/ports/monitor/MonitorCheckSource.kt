package skillbill.ports.monitor

import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity

/**
 * Read-only CI observation for the standalone monitor. Every call names the pull request by repository and number;
 * no call resolves the caller's branch or working directory.
 */
interface MonitorCheckSource {
  fun checks(pullRequest: MonitorPullRequestIdentity): PullRequestChecks

  /** Reads every check run and status context reported on the exact commit [sha] of [repository]. */
  fun commitChecks(
    repository: MonitorRepositoryIdentity,
    sha: String,
  ): PullRequestChecks

  /** Reads the failing-job log of [check] for [pullRequest], truncated to a bounded tail. */
  fun failureLog(
    pullRequest: MonitorPullRequestIdentity,
    check: PullRequestCheck,
  ): MonitorFailureLog
}
