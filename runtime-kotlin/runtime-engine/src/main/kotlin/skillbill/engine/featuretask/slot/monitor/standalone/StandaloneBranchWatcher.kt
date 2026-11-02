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
  private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
  private val startTimeout: Duration = DEFAULT_START_TIMEOUT,
  private val progressInterval: Duration = DEFAULT_PROGRESS_INTERVAL,
) {
  private class WatchState(
    val identity: MonitorBranchIdentity,
    val adoptHead: (String) -> String?,
    val onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    var expected: String,
    var expectedSince: Instant,
    var ciStarted: Boolean = false,
    var progress: StandaloneWatchProgress = StandaloneWatchProgress(),
  )

  fun watch(
    identity: MonitorBranchIdentity,
    expectedHead: String,
    adoptHead: (String) -> String?,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  ): PullRequestCiOutcome {
    val state = WatchState(identity, adoptHead, onCiRunning, expectedHead, now())
    while (true) {
      val outcome =
        if (cancelled()) {
          PullRequestCiOutcome.Blocked(StandalonePullRequestWatcher.CANCELLED_REASON)
        } else {
          poll(state)
        }
      outcome?.let { return it }
      sleep(pollInterval)
    }
  }

  private fun poll(state: WatchState): PullRequestCiOutcome? =
    when (val lookup = readBranch(state)) {
      is MonitorBranchLookup.Failed -> null
      MonitorBranchLookup.Missing -> deleted(state.identity)
      is MonitorBranchLookup.Found -> {
        val tip = lookup.head.tip.sha
        if (tip == state.expected) pollChecks(state) else follow(tip, state)
      }
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
    val current = readBranch(state) as? MonitorBranchLookup.Found
    return if (current?.head?.tip?.sha == tip) {
      PullRequestCiOutcome.Blocked("The branch moved to $tip and monitoring could not follow it: $refusal")
    } else {
      null
    }
  }

  private fun pollChecks(state: WatchState): PullRequestCiOutcome? {
    val reported = checks.commitChecks(state.identity.repository, state.expected)
    if (reported is PullRequestChecks.Unavailable) return null
    val reportedChecks = (reported as? PullRequestChecks.Reported)?.checks.orEmpty()
    if (reportedChecks.isNotEmpty()) state.ciStarted = true
    val verdict = standaloneVerdictFor(reportedChecks, state.expected, null)
    if (verdict != null) return confirmed(verdict, reportedChecks, state)
    state.progress =
      reportStandaloneCiRunning(
        state.identity.url,
        reportedChecks,
        state.onCiRunning,
        state.progress,
        now(),
        progressInterval,
      )
    return startTimedOut(state)
  }

  private fun confirmed(
    verdict: PullRequestCiOutcome,
    reportedChecks: List<PullRequestCheck>,
    state: WatchState,
  ): PullRequestCiOutcome? =
    when (val lookup = readBranch(state)) {
      is MonitorBranchLookup.Failed -> null
      MonitorBranchLookup.Missing -> deleted(state.identity)
      is MonitorBranchLookup.Found ->
        if (lookup.head.tip.sha == state.expected) withCancelledNames(verdict, reportedChecks) else null
    }

  private fun withCancelledNames(
    verdict: PullRequestCiOutcome,
    reportedChecks: List<PullRequestCheck>,
  ): PullRequestCiOutcome =
    if (verdict is PullRequestCiOutcome.Passed) {
      verdict.copy(cancelled = reportedChecks.filter { it.bucket == CheckBucket.CANCEL }.map(PullRequestCheck::name))
    } else {
      verdict
    }

  private fun startTimedOut(state: WatchState): PullRequestCiOutcome? =
    PullRequestCiOutcome.Blocked("CI did not start within ${startTimeout.toMinutes()} minutes on ${state.expected}.")
      .takeIf { !state.ciStarted && Duration.between(state.expectedSince, now()) >= startTimeout }

  private fun readBranch(state: WatchState): MonitorBranchLookup =
    source.branch(state.identity.repository, state.identity.ref)

  private fun deleted(identity: MonitorBranchIdentity): PullRequestCiOutcome =
    PullRequestCiOutcome.Blocked("Branch ${identity.ref} no longer exists on ${identity.repository.slug}.")
}
