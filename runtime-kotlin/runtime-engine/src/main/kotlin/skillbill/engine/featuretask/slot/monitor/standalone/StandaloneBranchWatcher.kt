package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.model.MonitorBranchIdentity
import skillbill.ports.monitor.model.MonitorBranchLookup
import java.time.Duration
import java.time.Instant

internal class StandaloneBranchWatcher(
  private val source: MonitorPullRequestSource,
  private val checks: MonitorCheckSource,
  private val now: () -> Instant,
  private val sleep: (Duration) -> Unit,
  private val cancelled: () -> Boolean,
) {
  private class WatchState(
    val branch: MonitorBranchIdentity,
    var expected: String,
    var expectedSince: Instant,
    val adoptHead: (String) -> String?,
    val onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    val progress: StandaloneWatchProgress,
    var ciStarted: Boolean = false,
  )

  fun watch(
    branch: MonitorBranchIdentity,
    expectedSha: String,
    adoptHead: (String) -> String?,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  ): PullRequestCiOutcome {
    val progress = StandaloneWatchProgress(now, DEFAULT_PROGRESS_INTERVAL)
    val state = WatchState(branch, expectedSha, now(), adoptHead, onCiRunning, progress)
    while (true) {
      val outcome =
        if (cancelled()) {
          PullRequestCiOutcome.Blocked(StandalonePullRequestWatcher.CANCELLED_REASON)
        } else {
          poll(state)
        }
      outcome?.let { return it }
      sleep(DEFAULT_POLL_INTERVAL)
    }
  }

  private fun poll(state: WatchState): PullRequestCiOutcome? =
    when (val lookup = readBranch(state.branch)) {
      is MonitorBranchLookup.Failed -> null
      MonitorBranchLookup.Missing -> deleted(state.branch)
      is MonitorBranchLookup.Found ->
        if (lookup.head.tip.sha == state.expected) observeChecks(state) else follow(lookup.head.tip.sha, state)
    }

  private fun follow(
    tip: String,
    state: WatchState,
  ): PullRequestCiOutcome? {
    val refusal = state.adoptHead(tip)
    if (refusal == null) {
      state.expected = tip
      state.expectedSince = now()
      state.ciStarted = false
      return null
    }
    val current = readBranch(state.branch) as? MonitorBranchLookup.Found
    return PullRequestCiOutcome.Blocked("The branch moved to $tip and monitoring could not follow it: $refusal")
      .takeIf { current?.head?.tip?.sha == tip }
  }

  private fun observeChecks(state: WatchState): PullRequestCiOutcome? {
    val reported = checks.commitChecks(state.branch.repository, state.expected)
    if (reported is PullRequestChecks.Unavailable) return null
    val reportedChecks = (reported as? PullRequestChecks.Reported)?.checks.orEmpty()
    if (reportedChecks.isNotEmpty()) state.ciStarted = true
    val verdict = standaloneVerdictFor(reportedChecks, state.expected, editableFieldsDigest = null)
    if (verdict == null) {
      state.progress.report(state.branch.url, reportedChecks, state.onCiRunning)
      return startTimedOut(state)
    }
    return confirm(withCancelledChecks(verdict, reportedChecks), state)
  }

  private fun startTimedOut(state: WatchState): PullRequestCiOutcome? =
    PullRequestCiOutcome.Blocked(
      "CI did not start within ${DEFAULT_START_TIMEOUT.toMinutes()} minutes on ${state.expected}.",
    ).takeIf { !state.ciStarted && Duration.between(state.expectedSince, now()) >= DEFAULT_START_TIMEOUT }

  private fun withCancelledChecks(
    verdict: PullRequestCiOutcome,
    reportedChecks: List<PullRequestCheck>,
  ): PullRequestCiOutcome =
    if (verdict is PullRequestCiOutcome.Passed) {
      verdict.copy(cancelled = reportedChecks.filter { it.bucket == CheckBucket.CANCEL }.map(PullRequestCheck::name))
    } else {
      verdict
    }

  private fun confirm(
    verdict: PullRequestCiOutcome,
    state: WatchState,
  ): PullRequestCiOutcome? =
    when (val lookup = readBranch(state.branch)) {
      is MonitorBranchLookup.Failed -> null
      MonitorBranchLookup.Missing -> deleted(state.branch)
      is MonitorBranchLookup.Found -> verdict.takeIf { lookup.head.tip.sha == state.expected }
    }

  private fun readBranch(branch: MonitorBranchIdentity): MonitorBranchLookup =
    source.branch(branch.repository, branch.ref)

  private fun deleted(branch: MonitorBranchIdentity): PullRequestCiOutcome =
    PullRequestCiOutcome.Blocked("Branch ${branch.ref} no longer exists on ${branch.repository.slug}.")
}
