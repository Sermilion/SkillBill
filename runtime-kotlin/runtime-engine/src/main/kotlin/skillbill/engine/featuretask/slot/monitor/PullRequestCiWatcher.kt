package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

internal class PullRequestCiWatcher(
  private val identityLookup: PullRequestIdentityLookup,
  private val checksLookup: PullRequestChecksLookup,
  private val now: () -> Instant = Instant::now,
  private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
  private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
  private val startTimeout: Duration = DEFAULT_START_TIMEOUT,
  private val progressInterval: Duration = DEFAULT_PROGRESS_INTERVAL,
) {
  fun watch(
    repoRoot: Path,
    branch: String,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit = { _, _ -> },
    expectedHeadSha: String? = null,
    onInconclusive: (String) -> Unit = {},
  ): PullRequestCiOutcome {
    val request = WatchRequest(repoRoot, branch, expectedHeadSha)
    val startedAt = now()
    var ciStarted = false
    var inconclusiveReported = false
    var progress = CiProgress()

    fun reportInconclusive(reason: String) {
      if (!inconclusiveReported) {
        inconclusiveReported = true
        onInconclusive(reason)
      }
    }
    val reportChecks = { url: String, checks: List<PullRequestCheck> ->
      progress = reportRunning(url, checks, checksSettled(checks), onCiRunning, progress)
    }
    while (true) {
      val poll = observePoll(request, reportChecks, ::reportInconclusive)
      ciStarted = ciStarted || poll.started
      val settled = poll.outcome
      if (settled != null) return settled
      if (!ciStarted && Duration.between(startedAt, now()) >= startTimeout) {
        return PullRequestCiOutcome.Blocked(startTimeoutReason())
      }
      sleep(pollInterval)
    }
  }

  private data class WatchRequest(val repoRoot: Path, val branch: String, val expectedHeadSha: String?)

  private data class CiPoll(val outcome: PullRequestCiOutcome? = null, val started: Boolean = false)

  private fun observePoll(
    request: WatchRequest,
    onChecks: (String, List<PullRequestCheck>) -> Unit,
    onInconclusive: (String) -> Unit,
  ): CiPoll {
    val identity = currentIdentity(request.repoRoot, request.branch)
    val found =
      identity as? PullRequestIdentity.Found ?: return CiPoll(
        refreshedOutcome(request, identityVerdict(identity)),
      )
    if (found.conflicting) return CiPoll(PullRequestCiOutcome.Conflicted)
    return when (val checks = checksLookup.lookup(request.repoRoot, found.number)) {
      is PullRequestChecks.Unavailable ->
        CiPoll(
          refreshedUnavailable(request.repoRoot, request.branch, PullRequestCiOutcome.Unavailable(checks.reason)),
        )
      PullRequestChecks.NoChecks -> {
        onChecks(found.url, emptyList())
        CiPoll()
      }
      is PullRequestChecks.Reported -> {
        onChecks(found.url, checks.checks)
        val failingChecks = checks.checks.filter { it.bucket == CheckBucket.FAIL }
        when {
          failingChecks.isNotEmpty() || checksSettled(checks.checks) ->
            CiPoll(
              refreshedOutcome(
                request,
                refreshedTerminal(
                  request,
                  found,
                  failingChecks.takeIf {
                    it.isNotEmpty()
                  },
                  onInconclusive,
                ),
              ),
            )
          checks.checks.isNotEmpty() -> refreshedPending(request, found, onInconclusive)
          else -> CiPoll()
        }
      }
    }
  }

  private fun refreshedOutcome(
    request: WatchRequest,
    outcome: PullRequestCiOutcome?,
  ): PullRequestCiOutcome? =
    if (outcome is PullRequestCiOutcome.Unavailable) {
      refreshedUnavailable(request.repoRoot, request.branch, outcome)
    } else {
      outcome
    }

  private fun checksSettled(checks: List<PullRequestCheck>): Boolean =
    checks.isNotEmpty() && checks.none { it.bucket == CheckBucket.PENDING }

  private fun refreshedPending(
    request: WatchRequest,
    observed: PullRequestIdentity.Found,
    onInconclusive: (String) -> Unit,
  ): CiPoll =
    when (val latest = identityLookup.lookup(request.repoRoot, request.branch)) {
      is PullRequestIdentity.Merged -> CiPoll(PullRequestCiOutcome.Merged)
      is PullRequestIdentity.Unavailable -> CiPoll(PullRequestCiOutcome.Unavailable(latest.reason))
      PullRequestIdentity.Absent -> CiPoll(PullRequestCiOutcome.NoPullRequest)
      is PullRequestIdentity.Found ->
        when {
          latest.conflicting -> CiPoll(PullRequestCiOutcome.Conflicted)
          latest.number != observed.number || latest.headSha != observed.headSha -> {
            onInconclusive("Pull request identity changed during check observation.")
            CiPoll()
          }
          else -> CiPoll(started = true)
        }
    }

  private fun currentIdentity(
    repoRoot: Path,
    branch: String,
  ): PullRequestIdentity {
    val identity = identityLookup.lookup(repoRoot, branch)
    return if ((identity as? PullRequestIdentity.Found)?.conflicting == true) {
      identityLookup.lookup(repoRoot, branch)
    } else {
      identity
    }
  }

  private fun refreshedTerminal(
    request: WatchRequest,
    observedIdentity: PullRequestIdentity.Found,
    failingChecks: List<PullRequestCheck>?,
    onInconclusive: (String) -> Unit,
  ): PullRequestCiOutcome? =
    when (val latest = identityLookup.lookup(request.repoRoot, request.branch)) {
      is PullRequestIdentity.Merged -> PullRequestCiOutcome.Merged
      is PullRequestIdentity.Unavailable -> PullRequestCiOutcome.Unavailable(latest.reason)
      PullRequestIdentity.Absent -> PullRequestCiOutcome.NoPullRequest
      is PullRequestIdentity.Found ->
        if (latest.conflicting) {
          PullRequestCiOutcome.Conflicted
        } else if (latest.number != observedIdentity.number || latest.headSha != observedIdentity.headSha) {
          onInconclusive("Pull request identity changed during check observation.")
          null
        } else if (failingChecks != null) {
          PullRequestCiOutcome.Failed(failingChecks, observedIdentity.headSha)
        } else if (isExpectedHead(latest, request.expectedHeadSha)) {
          PullRequestCiOutcome.Passed(requireNotNull(latest.headSha))
        } else {
          onInconclusive("Green checks are not associated with the published pull request head.")
          null
        }
    }

  private fun refreshedUnavailable(
    repoRoot: Path,
    branch: String,
    observed: PullRequestCiOutcome.Unavailable,
  ): PullRequestCiOutcome =
    when (val latest = identityLookup.lookup(repoRoot, branch)) {
      is PullRequestIdentity.Merged -> PullRequestCiOutcome.Merged
      is PullRequestIdentity.Unavailable -> PullRequestCiOutcome.Unavailable(latest.reason)
      PullRequestIdentity.Absent -> PullRequestCiOutcome.NoPullRequest
      is PullRequestIdentity.Found -> if (latest.conflicting) PullRequestCiOutcome.Conflicted else observed
    }

  private fun isExpectedHead(
    identity: PullRequestIdentity.Found,
    expectedHeadSha: String?,
  ): Boolean =
    identity.number > 0 && !expectedHeadSha.isNullOrBlank() && !identity.headSha.isNullOrBlank() &&
      identity.headSha == expectedHeadSha

  private data class CiProgress(val at: Instant? = null, val names: List<String>? = null)

  private fun reportRunning(
    pullRequestUrl: String,
    checks: List<PullRequestCheck>,
    settled: Boolean,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    previous: CiProgress,
  ): CiProgress {
    if (settled) return previous
    val pending = checks.filter { it.bucket == CheckBucket.PENDING }
    val names = pending.map(PullRequestCheck::name)
    val due = previous.at == null || Duration.between(previous.at, now()) >= progressInterval
    if (!due && names == previous.names) return previous
    onCiRunning(progressLink(pullRequestUrl), pending.take(MAX_PROGRESS_CHECKS).map(::progressCheck))
    return CiProgress(now(), names)
  }

  private fun progressCheck(check: PullRequestCheck): PullRequestCheck =
    check.copy(name = check.name.take(MAX_PROGRESS_TEXT_LENGTH), link = progressLink(check.link))

  private fun progressLink(value: String): String =
    runCatching {
      val uri = URI(value)
      val scheme = uri.scheme?.takeIf { it == "http" || it == "https" }
      val host = uri.host?.takeIf(String::isNotBlank)
      if (scheme == null || host == null) {
        ""
      } else {
        URI(scheme, null, host, uri.port, uri.path, null, null)
          .toASCIIString()
          .takeIf { it.length <= MAX_PROGRESS_LINK_LENGTH }
          .orEmpty()
      }
    }.getOrDefault("")

  private fun identityVerdict(identity: PullRequestIdentity): PullRequestCiOutcome? =
    when (identity) {
      is PullRequestIdentity.Found -> null
      is PullRequestIdentity.Merged -> PullRequestCiOutcome.Merged
      PullRequestIdentity.Absent -> PullRequestCiOutcome.NoPullRequest
      is PullRequestIdentity.Unavailable -> PullRequestCiOutcome.Unavailable(identity.reason)
    }

  private fun startTimeoutReason(): String = "CI did not start within ${startTimeout.toMinutes()} minutes."

  private companion object {
    val DEFAULT_POLL_INTERVAL: Duration = Duration.ofSeconds(30)
    val DEFAULT_START_TIMEOUT: Duration = Duration.ofHours(1)
    val DEFAULT_PROGRESS_INTERVAL: Duration = Duration.ofSeconds(30)
    const val MAX_PROGRESS_CHECKS: Int = 8
    const val MAX_PROGRESS_TEXT_LENGTH: Int = 160
    const val MAX_PROGRESS_LINK_LENGTH: Int = 300
  }
}
