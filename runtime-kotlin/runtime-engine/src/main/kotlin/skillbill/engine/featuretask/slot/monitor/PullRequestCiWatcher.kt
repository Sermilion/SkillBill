package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.slot.state.ExpectedCiHead
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.net.URI
import java.net.URISyntaxException
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
  private val refreshGrace: Duration = DEFAULT_REFRESH_GRACE,
) {
  fun watch(
    repoRoot: Path,
    branch: String,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit = { _, _ -> },
    expectedHead: ExpectedCiHead = ExpectedCiHead(),
    onInconclusive: (String) -> Unit = {},
  ): PullRequestCiOutcome {
    val startedAt = now()
    val refresh = expectedHead.refreshAfter?.let { CheckRefresh(it, startedAt + refreshGrace) }
    val request = WatchRequest(repoRoot, branch, refresh)
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
      val poll = observePoll(request, reportChecks, ::reportInconclusive, onInconclusive)
      ciStarted = ciStarted || poll.started
      val settled = poll.outcome
      if (settled != null) return settled
      val awaitingStart = !poll.unknown && poll.startTimeoutEligible && !ciStarted
      if (
        awaitingStart &&
        Duration.between(startedAt, now()) >= startTimeout
      ) {
        return PullRequestCiOutcome.Blocked(startTimeoutReason())
      }
      sleep(pollInterval)
    }
  }

  private data class WatchRequest(
    val repoRoot: Path,
    val branch: String,
    val refresh: CheckRefresh?,
  )

  private data class CheckRefresh(val after: Instant, val graceEndsAt: Instant)

  private data class CiPoll(
    val outcome: PullRequestCiOutcome? = null,
    val started: Boolean = false,
    val unknown: Boolean = false,
    val startTimeoutEligible: Boolean = false,
  )

  private fun observePoll(
    request: WatchRequest,
    onChecks: (String, List<PullRequestCheck>) -> Unit,
    onInconclusive: (String) -> Unit,
    onReadFailure: (String) -> Unit,
  ): CiPoll {
    val identity = currentIdentity(request.repoRoot, request.branch, onReadFailure)
    val found = identity as? PullRequestIdentity.Found ?: return identityPoll(request, identity, onReadFailure)
    if (found.conflicting) return CiPoll(PullRequestCiOutcome.Conflicted)
    return when (val checks = checksLookup.lookup(request.repoRoot, found.number)) {
      is PullRequestChecks.Unavailable ->
        checksLookupFailure(request, checks.reason, onReadFailure)
      PullRequestChecks.NoChecks -> {
        onChecks(found.url, emptyList())
        CiPoll(startTimeoutEligible = true)
      }
      is PullRequestChecks.Reported -> {
        onChecks(found.url, checks.checks)
        val failingChecks = checks.checks.filter { it.bucket == CheckBucket.FAIL }
        when {
          failingChecks.isNotEmpty() && !refreshed(request.refresh, checks.checks) -> CiPoll(started = true)
          failingChecks.isNotEmpty() || checksSettled(checks.checks) ->
            refreshedTerminal(
              request,
              found,
              failingChecks.takeIf { it.isNotEmpty() },
              onInconclusive,
              onReadFailure,
            )
          checks.checks.isNotEmpty() -> refreshedPending(request, found, onInconclusive, onReadFailure)
          else -> CiPoll(startTimeoutEligible = true)
        }
      }
    }
  }

  private fun refreshed(
    refresh: CheckRefresh?,
    checks: List<PullRequestCheck>,
  ): Boolean =
    refresh == null ||
      checksSettled(checks) &&
      (
        checks.any { check -> check.startedAt?.let { !it.isBefore(refresh.after) } == true } ||
          !now().isBefore(refresh.graceEndsAt)
      )

  private fun identityPoll(
    request: WatchRequest,
    identity: PullRequestIdentity,
    onReadFailure: (String) -> Unit,
  ): CiPoll =
    when (identity) {
      is PullRequestIdentity.Unavailable -> identityAfterUnknown(request, onReadFailure)
      is PullRequestIdentity.Merged -> CiPoll(PullRequestCiOutcome.Merged)
      PullRequestIdentity.Absent -> CiPoll(PullRequestCiOutcome.NoPullRequest)
      is PullRequestIdentity.Found -> if (identity.conflicting) CiPoll(PullRequestCiOutcome.Conflicted) else CiPoll()
    }

  private fun identityAfterUnknown(
    request: WatchRequest,
    onReadFailure: (String) -> Unit,
  ): CiPoll =
    when (val latest = lookupIdentity(request.repoRoot, request.branch, onReadFailure)) {
      is PullRequestIdentity.Merged -> CiPoll(PullRequestCiOutcome.Merged)
      PullRequestIdentity.Absent -> CiPoll(PullRequestCiOutcome.NoPullRequest)
      is PullRequestIdentity.Unavailable -> CiPoll(unknown = true)
      is PullRequestIdentity.Found ->
        if (latest.conflicting) CiPoll(PullRequestCiOutcome.Conflicted) else CiPoll(unknown = true)
    }

  private fun checksLookupFailure(
    request: WatchRequest,
    reason: String,
    onReadFailure: (String) -> Unit,
  ): CiPoll {
    readFailure(onReadFailure, "GitHub CI checks lookup failed: $reason")
    return identityAfterUnknown(request, onReadFailure)
  }

  private fun checksSettled(checks: List<PullRequestCheck>): Boolean =
    checks.isNotEmpty() && checks.none { it.bucket == CheckBucket.PENDING }

  private fun refreshedPending(
    request: WatchRequest,
    observed: PullRequestIdentity.Found,
    onInconclusive: (String) -> Unit,
    onReadFailure: (String) -> Unit,
  ): CiPoll =
    when (val latest = lookupIdentity(request.repoRoot, request.branch, onReadFailure)) {
      is PullRequestIdentity.Merged -> CiPoll(PullRequestCiOutcome.Merged)
      is PullRequestIdentity.Unavailable -> CiPoll(unknown = true)
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
    onReadFailure: (String) -> Unit,
  ): PullRequestIdentity {
    val identity = lookupIdentity(repoRoot, branch, onReadFailure)
    return if ((identity as? PullRequestIdentity.Found)?.conflicting == true) {
      lookupIdentity(repoRoot, branch, onReadFailure)
    } else {
      identity
    }
  }

  private fun lookupIdentity(
    repoRoot: Path,
    branch: String,
    onReadFailure: (String) -> Unit,
  ): PullRequestIdentity =
    identityLookup.lookup(repoRoot, branch).also { identity ->
      if (identity is PullRequestIdentity.Unavailable) {
        readFailure(onReadFailure, "GitHub pull request identity lookup failed: ${identity.reason}")
      }
    }

  private fun refreshedTerminal(
    request: WatchRequest,
    observedIdentity: PullRequestIdentity.Found,
    failingChecks: List<PullRequestCheck>?,
    onInconclusive: (String) -> Unit,
    onReadFailure: (String) -> Unit,
  ): CiPoll =
    when (val latest = lookupIdentity(request.repoRoot, request.branch, onReadFailure)) {
      is PullRequestIdentity.Merged -> CiPoll(PullRequestCiOutcome.Merged)
      is PullRequestIdentity.Unavailable -> CiPoll(unknown = true)
      PullRequestIdentity.Absent -> CiPoll(PullRequestCiOutcome.NoPullRequest)
      is PullRequestIdentity.Found ->
        if (latest.conflicting) {
          CiPoll(PullRequestCiOutcome.Conflicted)
        } else if (latest.number != observedIdentity.number || latest.headSha != observedIdentity.headSha) {
          onInconclusive("Pull request identity changed during check observation.")
          CiPoll()
        } else if (failingChecks != null) {
          CiPoll(PullRequestCiOutcome.Failed(failingChecks, observedIdentity.headSha, latest.editableFieldsDigest))
        } else {
          latest.headSha?.takeIf(String::isNotBlank)?.let { CiPoll(PullRequestCiOutcome.Passed(it)) } ?: run {
            onInconclusive("Green checks have no pull request head to attribute them to.")
            CiPoll()
          }
        }
    }

  private fun readFailure(
    onReadFailure: (String) -> Unit,
    reason: String,
  ) {
    onReadFailure(reason.take(MAX_FAILURE_CONTEXT_LENGTH))
  }

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
    try {
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
    } catch (_: URISyntaxException) {
      ""
    }

  private fun startTimeoutReason(): String = "CI did not start within ${startTimeout.toMinutes()} minutes."

  private companion object {
    val DEFAULT_POLL_INTERVAL: Duration = Duration.ofSeconds(30)
    val DEFAULT_START_TIMEOUT: Duration = Duration.ofHours(1)
    val DEFAULT_PROGRESS_INTERVAL: Duration = Duration.ofSeconds(30)
    val DEFAULT_REFRESH_GRACE: Duration = Duration.ofMinutes(10)
    const val MAX_PROGRESS_CHECKS: Int = 8
    const val MAX_PROGRESS_TEXT_LENGTH: Int = 160
    const val MAX_PROGRESS_LINK_LENGTH: Int = 300
    const val MAX_FAILURE_CONTEXT_LENGTH: Int = 300
  }
}
