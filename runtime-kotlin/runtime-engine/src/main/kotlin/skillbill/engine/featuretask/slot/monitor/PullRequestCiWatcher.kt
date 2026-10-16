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
  ): PullRequestCiOutcome {
    val startedAt = now()
    var ciStarted = false
    var lastProgressAt: Instant? = null
    var lastPendingNames: List<String>? = null
    while (true) {
      val identity = currentIdentity(repoRoot, branch)
      val found = identity as? PullRequestIdentity.Found
      if (found?.conflicting == true) return PullRequestCiOutcome.Conflicted
      val prNumber = found?.number
      val settled =
        if (prNumber == null) {
          identityVerdict(identity)
        } else {
          when (val checks = checksLookup.lookup(repoRoot, prNumber)) {
            is PullRequestChecks.Unavailable -> PullRequestCiOutcome.Unavailable(checks.reason)
            PullRequestChecks.NoChecks -> {
              reportRunning(
                found.url,
                emptyList(),
                verdict = null,
                onCiRunning = onCiRunning,
                lastProgressAt = lastProgressAt,
                lastPendingNames = lastPendingNames,
              )
                ?.let { (reportedAt, pendingNames) ->
                  lastProgressAt = reportedAt
                  lastPendingNames = pendingNames
                }
              null
            }
            is PullRequestChecks.Reported -> {
              if (checks.checks.isNotEmpty()) {
                ciStarted = true
              }
              val verdict = verdictFor(checks.checks)
              reportRunning(
                found.url,
                checks.checks,
                verdict,
                onCiRunning,
                lastProgressAt,
                lastPendingNames,
              )
                ?.let { (reportedAt, pendingNames) ->
                  lastProgressAt = reportedAt
                  lastPendingNames = pendingNames
                }
              verdict
            }
          }
        }
      if (settled != null) {
        return if (settled is PullRequestCiOutcome.Failed || settled is PullRequestCiOutcome.Unavailable) {
          refreshedFailure(repoRoot, branch, settled)
        } else {
          settled
        }
      }
      if (!ciStarted && Duration.between(startedAt, now()) >= startTimeout) {
        return PullRequestCiOutcome.Blocked(startTimeoutReason())
      }
      sleep(pollInterval)
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

  private fun refreshedFailure(
    repoRoot: Path,
    branch: String,
    observed: PullRequestCiOutcome,
  ): PullRequestCiOutcome =
    when (val latest = identityLookup.lookup(repoRoot, branch)) {
      is PullRequestIdentity.Merged -> PullRequestCiOutcome.Merged
      is PullRequestIdentity.Unavailable -> PullRequestCiOutcome.Unavailable(latest.reason)
      PullRequestIdentity.Absent -> PullRequestCiOutcome.NoPullRequest
      is PullRequestIdentity.Found -> observed
    }

  private fun reportRunning(
    pullRequestUrl: String,
    checks: List<PullRequestCheck>,
    verdict: PullRequestCiOutcome?,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    lastProgressAt: Instant?,
    lastPendingNames: List<String>?,
  ): Pair<Instant, List<String>>? {
    if (verdict != null) return null
    val pending = checks.filter { it.bucket == CheckBucket.PENDING }
    val names = pending.map(PullRequestCheck::name)
    val due = lastProgressAt == null || Duration.between(lastProgressAt, now()) >= progressInterval
    if (!due && names == lastPendingNames) return null
    onCiRunning(progressLink(pullRequestUrl), pending.take(MAX_PROGRESS_CHECKS).map(::progressCheck))
    return now() to names
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

  private fun verdictFor(checks: List<PullRequestCheck>): PullRequestCiOutcome? {
    val failing = checks.filter { it.bucket == CheckBucket.FAIL }
    return when {
      failing.isNotEmpty() -> PullRequestCiOutcome.Failed(failing)
      checks.isEmpty() || checks.any { it.bucket == CheckBucket.PENDING } -> null
      else -> PullRequestCiOutcome.Passed
    }
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
