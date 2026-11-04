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
  private val noChecksGrace: Duration = DEFAULT_NO_CHECKS_GRACE,
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

  private class WatchState(
    var heads: StandaloneWatchHeads,
    val adoptHead: (String) -> String?,
    val refreshGraceEndsAt: Instant,
    var ciStarted: Boolean = false,
    var emptyObservedAt: Instant? = null,
    val seenCheckHeads: MutableSet<String> = mutableSetOf(),
    val progress: StandaloneWatchProgress,
  )

  fun watch(
    pullRequest: MonitorPullRequestIdentity,
    heads: StandaloneWatchHeads,
    adoptHead: (String) -> String?,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  ): PullRequestCiOutcome {
    val startedAt = now()
    val progress = StandaloneWatchProgress(now, progressInterval)
    val state = WatchState(heads, adoptHead, startedAt + refreshGrace, progress = progress)
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
      Observation.NotYetVisible -> {
        state.emptyObservedAt = null
        PollRound()
      }
      Observation.Unknown -> {
        state.emptyObservedAt = null
        PollRound()
      }
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
    state.emptyObservedAt = null
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
      state.emptyObservedAt = null
      return confirmUnavailable(pullRequest, state.heads)
    }
    val reportedChecks = (reported as? PullRequestChecks.Reported)?.checks.orEmpty()
    if (reportedChecks.isNotEmpty()) {
      state.ciStarted = true
      state.seenCheckHeads += open.headSha
      state.emptyObservedAt = null
    }
    val verdict =
      standaloneVerdictFor(reportedChecks, open.headSha, open.pullRequest.editableFieldsDigest)
        ?.takeUnless { it is PullRequestCiOutcome.Failed && !refreshed(reportedChecks, state) }
    if (verdict == null) {
      state.progress.report(pullRequest.url, reportedChecks, onCiRunning)
      if (reportedChecks.isEmpty()) {
        val outcome = confirmEmpty(pullRequest, open, state)
        if (outcome != null) return outcome
      }
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

  private fun confirmEmpty(
    pullRequest: MonitorPullRequestIdentity,
    open: Observation.Open,
    state: WatchState,
  ): PollRound? {
    if (open.headSha in state.seenCheckHeads) {
      state.emptyObservedAt = null
      return null
    }
    val firstEmptyAt = state.emptyObservedAt
    if (firstEmptyAt == null) {
      state.emptyObservedAt = now()
      return null
    }
    if (Duration.between(firstEmptyAt, now()) < noChecksGrace) return null
    return when (val confirmation = observe(pullRequest, state.heads)) {
      is Observation.Settled -> PollRound(confirmation.outcome)
      Observation.Unknown, Observation.NotYetVisible -> {
        state.emptyObservedAt = null
        null
      }
      is Observation.Moved -> {
        state.emptyObservedAt = null
        PollRound(adopt(confirmation.headSha, state))
      }
      is Observation.Open -> {
        if (confirmation.headSha == open.headSha) {
          PollRound(PullRequestCiOutcome.NoChecksReported)
        } else {
          state.emptyObservedAt = null
          null
        }
      }
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
      headSha.isNullOrBlank() ->
        Observation.Settled(PullRequestCiOutcome.Unavailable("The pull request head is unavailable."))
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

  companion object {
    const val CANCELLED_REASON: String = "Monitoring was cancelled."
  }
}
