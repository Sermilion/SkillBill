package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.model.MonitorPullRequest
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestState
import java.time.Duration
import java.time.Instant

internal data class StandaloneWatchHeads(
  val expected: String,
  val superseded: Set<String>,
)

internal class StandalonePullRequestWatcher(
  private val source: MonitorPullRequestSource,
  private val checks: MonitorCheckSource,
  private val now: () -> Instant,
  private val sleep: (Duration) -> Unit,
  private val cancelled: () -> Boolean,
  private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
  private val startTimeout: Duration = DEFAULT_START_TIMEOUT,
  private val progressInterval: Duration = DEFAULT_PROGRESS_INTERVAL,
) {
  private sealed interface Observation {
    data class Settled(val outcome: PullRequestCiOutcome) : Observation

    data class Open(val pullRequest: MonitorPullRequest, val headSha: String) : Observation

    data object NotYetVisible : Observation
  }

  private data class Progress(val at: Instant? = null, val names: List<String>? = null)

  private class WatchState(var ciStarted: Boolean = false, var progress: Progress = Progress())

  fun watch(
    pullRequest: MonitorPullRequestIdentity,
    heads: StandaloneWatchHeads,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  ): PullRequestCiOutcome {
    val startedAt = now()
    val state = WatchState()
    while (true) {
      val outcome =
        if (cancelled()) {
          PullRequestCiOutcome.Blocked(CANCELLED_REASON)
        } else {
          poll(pullRequest, heads, onCiRunning, state) ?: startTimeout(startedAt, state)
        }
      if (outcome != null) return outcome
      sleep(pollInterval)
    }
  }

  private fun poll(
    pullRequest: MonitorPullRequestIdentity,
    heads: StandaloneWatchHeads,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    state: WatchState,
  ): PullRequestCiOutcome? =
    when (val observation = observe(pullRequest, heads)) {
      is Observation.Settled -> observation.outcome
      Observation.NotYetVisible -> null
      is Observation.Open -> pollChecks(pullRequest, heads, observation.headSha, onCiRunning, state)
    }

  private fun pollChecks(
    pullRequest: MonitorPullRequestIdentity,
    heads: StandaloneWatchHeads,
    headSha: String,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    state: WatchState,
  ): PullRequestCiOutcome? {
    val reported = checks.checks(pullRequest)
    if (reported is PullRequestChecks.Unavailable) {
      return confirmed(pullRequest, heads, PullRequestCiOutcome.Unavailable(reported.reason))
    }
    val reportedChecks = (reported as? PullRequestChecks.Reported)?.checks.orEmpty()
    if (reportedChecks.isNotEmpty()) state.ciStarted = true
    val verdict = verdictFor(reportedChecks, headSha)
    if (verdict == null) {
      state.progress = reportRunning(pullRequest, reportedChecks, onCiRunning, state.progress)
      return null
    }
    return confirmed(pullRequest, heads, verdict)
  }

  private fun startTimeout(
    startedAt: Instant,
    state: WatchState,
  ): PullRequestCiOutcome? =
    PullRequestCiOutcome.Blocked("CI did not start within ${startTimeout.toMinutes()} minutes.")
      .takeIf { !state.ciStarted && Duration.between(startedAt, now()) >= startTimeout }

  private fun observe(
    pullRequest: MonitorPullRequestIdentity,
    heads: StandaloneWatchHeads,
  ): Observation =
    when (val lookup = source.pullRequest(pullRequest.repository, pullRequest.number)) {
      is MonitorPullRequestLookup.Failed -> Observation.Settled(PullRequestCiOutcome.Unavailable(lookup.reason))
      MonitorPullRequestLookup.Missing -> Observation.Settled(PullRequestCiOutcome.NoPullRequest)
      is MonitorPullRequestLookup.Found -> observeFound(lookup.pullRequest, heads)
    }

  private fun observeFound(
    pullRequest: MonitorPullRequest,
    heads: StandaloneWatchHeads,
  ): Observation {
    val headSha = pullRequest.head?.tip?.sha
    return when {
      pullRequest.state == MonitorPullRequestState.MERGED -> Observation.Settled(PullRequestCiOutcome.Merged)
      pullRequest.state == MonitorPullRequestState.CLOSED ->
        Observation.Settled(PullRequestCiOutcome.Blocked("The pull request was closed without merging."))
      headSha == null -> Observation.Settled(PullRequestCiOutcome.Unavailable("The pull request head is unavailable."))
      headSha == heads.expected && pullRequest.conflicting -> Observation.Settled(PullRequestCiOutcome.Conflicted)
      headSha == heads.expected -> Observation.Open(pullRequest, headSha)
      headSha in heads.superseded -> Observation.NotYetVisible
      else ->
        Observation.Settled(
          PullRequestCiOutcome.Blocked(
            "The pull request head moved to $headSha while monitoring expected ${heads.expected}.",
          ),
        )
    }
  }

  private fun confirmed(
    pullRequest: MonitorPullRequestIdentity,
    heads: StandaloneWatchHeads,
    observed: PullRequestCiOutcome,
  ): PullRequestCiOutcome =
    when (val latest = observe(pullRequest, heads)) {
      is Observation.Settled -> latest.outcome
      Observation.NotYetVisible ->
        PullRequestCiOutcome.Unavailable("The pull request head changed while its checks were being observed.")
      is Observation.Open -> observed
    }

  private fun verdictFor(
    reportedChecks: List<PullRequestCheck>,
    headSha: String,
  ): PullRequestCiOutcome? {
    val failing = reportedChecks.filter { it.bucket == CheckBucket.FAIL }
    return when {
      failing.isNotEmpty() -> PullRequestCiOutcome.Failed(failing, headSha)
      reportedChecks.isEmpty() || reportedChecks.any { it.bucket == CheckBucket.PENDING } -> null
      else -> PullRequestCiOutcome.Passed(headSha)
    }
  }

  private fun reportRunning(
    pullRequest: MonitorPullRequestIdentity,
    reportedChecks: List<PullRequestCheck>,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    previous: Progress,
  ): Progress {
    val pending = reportedChecks.filter { it.bucket == CheckBucket.PENDING }
    val names = pending.map(PullRequestCheck::name)
    val due = previous.at == null || Duration.between(previous.at, now()) >= progressInterval
    if (!due && names == previous.names) return previous
    onCiRunning(
      pullRequest.url,
      pending.take(MAX_PROGRESS_CHECKS).map { it.copy(name = it.name.take(MAX_PROGRESS_TEXT_LENGTH), link = "") },
    )
    return Progress(now(), names)
  }

  companion object {
    const val CANCELLED_REASON: String = "Monitoring was cancelled."

    private val DEFAULT_POLL_INTERVAL: Duration = Duration.ofSeconds(30)
    private val DEFAULT_START_TIMEOUT: Duration = Duration.ofHours(1)
    private val DEFAULT_PROGRESS_INTERVAL: Duration = Duration.ofSeconds(30)
    private const val MAX_PROGRESS_CHECKS: Int = 8
    private const val MAX_PROGRESS_TEXT_LENGTH: Int = 160
  }
}
