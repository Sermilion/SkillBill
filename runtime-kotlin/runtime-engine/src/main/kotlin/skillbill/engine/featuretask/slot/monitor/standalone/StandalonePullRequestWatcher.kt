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
  val refreshAfter: Instant? = null,
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
  private val refreshGrace: Duration = DEFAULT_REFRESH_GRACE,
) {
  private sealed interface Observation {
    data class Settled(val outcome: PullRequestCiOutcome) : Observation

    data class Open(val pullRequest: MonitorPullRequest, val headSha: String) : Observation

    data class Moved(val headSha: String) : Observation

    data object NotYetVisible : Observation

    data object Unknown : Observation
  }

  private data class PollRound(
    val outcome: PullRequestCiOutcome? = null,
    val startTimeoutEligible: Boolean = false,
  )

  private data class Progress(val at: Instant? = null, val names: List<String>? = null)

  private class WatchState(
    var heads: StandaloneWatchHeads,
    val adoptHead: (String) -> String?,
    val refreshGraceEndsAt: Instant,
    var ciStarted: Boolean = false,
    var progress: Progress = Progress(),
  )

  fun watch(
    pullRequest: MonitorPullRequestIdentity,
    heads: StandaloneWatchHeads,
    adoptHead: (String) -> String?,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  ): PullRequestCiOutcome {
    val startedAt = now()
    val state = WatchState(heads, adoptHead, startedAt + refreshGrace)
    while (true) {
      val round =
        if (cancelled()) {
          PollRound(PullRequestCiOutcome.Blocked(CANCELLED_REASON))
        } else {
          poll(pullRequest, onCiRunning, state)
        }
      round.outcome?.let { return it }
      if (round.startTimeoutEligible) startTimeout(startedAt, state)?.let { return it }
      sleep(pollInterval)
    }
  }

  private fun poll(
    pullRequest: MonitorPullRequestIdentity,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    state: WatchState,
  ): PollRound =
    when (val observation = observe(pullRequest, state.heads)) {
      is Observation.Settled -> PollRound(observation.outcome)
      Observation.NotYetVisible, Observation.Unknown -> PollRound()
      is Observation.Moved -> PollRound(adopt(observation.headSha, state))
      is Observation.Open -> pollChecks(pullRequest, observation, onCiRunning, state)
    }

  private fun adopt(
    headSha: String,
    state: WatchState,
  ): PullRequestCiOutcome? {
    val refusal = state.adoptHead(headSha)
    if (refusal != null) {
      return PullRequestCiOutcome.Blocked(
        "The pull request head moved to $headSha and monitoring could not follow it: $refusal",
      )
    }
    state.heads = StandaloneWatchHeads(headSha, state.heads.superseded + state.heads.expected)
    return null
  }

  private fun pollChecks(
    pullRequest: MonitorPullRequestIdentity,
    open: Observation.Open,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    state: WatchState,
  ): PollRound {
    val reported = checks.checks(pullRequest)
    if (reported is PullRequestChecks.Unavailable) {
      return confirmUnavailable(pullRequest, state.heads)
    }
    val reportedChecks = (reported as? PullRequestChecks.Reported)?.checks.orEmpty()
    if (reportedChecks.isNotEmpty()) state.ciStarted = true
    val verdict =
      verdictFor(reportedChecks, open.headSha, open.pullRequest.editableFieldsDigest)
        ?.takeUnless { it is PullRequestCiOutcome.Failed && !refreshed(reportedChecks, state) }
    if (verdict == null) {
      state.progress = reportRunning(pullRequest, reportedChecks, onCiRunning, state.progress)
      return PollRound(startTimeoutEligible = reportedChecks.isEmpty())
    }
    return when (val confirmation = observe(pullRequest, state.heads)) {
      is Observation.Settled -> PollRound(confirmation.outcome)
      Observation.NotYetVisible ->
        PollRound(
          PullRequestCiOutcome.Unavailable("The pull request head changed while its checks were being observed."),
        )
      Observation.Unknown -> PollRound()
      is Observation.Moved -> PollRound()
      is Observation.Open -> PollRound(verdict)
    }
  }

  private fun confirmUnavailable(
    pullRequest: MonitorPullRequestIdentity,
    heads: StandaloneWatchHeads,
  ): PollRound =
    when (val confirmation = observe(pullRequest, heads)) {
      is Observation.Settled -> PollRound(confirmation.outcome)
      Observation.NotYetVisible ->
        PollRound(
          PullRequestCiOutcome.Unavailable("The pull request head changed while its checks were being observed."),
        )
      is Observation.Moved -> PollRound()
      is Observation.Open, Observation.Unknown -> PollRound()
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
      is MonitorPullRequestLookup.Failed -> Observation.Unknown
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
      else -> Observation.Moved(headSha)
    }
  }

  private fun refreshed(
    reportedChecks: List<PullRequestCheck>,
    state: WatchState,
  ): Boolean {
    val after = state.heads.refreshAfter ?: return true
    return reportedChecks.none { it.bucket == CheckBucket.PENDING } &&
      (
        reportedChecks.any { check -> check.startedAt?.let { !it.isBefore(after) } == true } ||
          !now().isBefore(state.refreshGraceEndsAt)
      )
  }

  private fun verdictFor(
    reportedChecks: List<PullRequestCheck>,
    headSha: String,
    editableFieldsDigest: String?,
  ): PullRequestCiOutcome? {
    val failing = reportedChecks.filter { it.bucket == CheckBucket.FAIL }
    return when {
      failing.isNotEmpty() -> PullRequestCiOutcome.Failed(failing, headSha, editableFieldsDigest)
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
    private val DEFAULT_REFRESH_GRACE: Duration = Duration.ofMinutes(10)
    private const val MAX_PROGRESS_CHECKS: Int = 8
    private const val MAX_PROGRESS_TEXT_LENGTH: Int = 160
  }
}
