package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
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
    onCiRunning: (List<PullRequestCheck>) -> Unit = {},
  ): PullRequestCiOutcome {
    val startedAt = now()
    var ciStarted = false
    var lastProgressAt: Instant? = null
    var lastPendingNames: List<String>? = null
    while (true) {
      val identity = identityLookup.lookup(repoRoot, branch)
      val found = identity as? PullRequestIdentity.Found
      if (found?.conflicting == true) return PullRequestCiOutcome.Conflicted
      val prNumber = found?.number
      val settled =
        if (prNumber == null) {
          identityVerdict(identity)
        } else {
          when (val checks = checksLookup.lookup(repoRoot, prNumber)) {
            is PullRequestChecks.Unavailable -> PullRequestCiOutcome.Unavailable(checks.reason)
            PullRequestChecks.NoChecks -> null
            is PullRequestChecks.Reported -> {
              if (checks.checks.isNotEmpty()) {
                ciStarted = true
              }
              val verdict = verdictFor(checks.checks)
              reportRunning(checks.checks, verdict, onCiRunning, lastProgressAt, lastPendingNames)
                ?.let { (reportedAt, pendingNames) ->
                  lastProgressAt = reportedAt
                  lastPendingNames = pendingNames
                }
              verdict
            }
          }
        }
      if (settled != null) return settled
      if (!ciStarted && Duration.between(startedAt, now()) >= startTimeout) {
        return PullRequestCiOutcome.Blocked(startTimeoutReason())
      }
      sleep(pollInterval)
    }
  }

  private fun reportRunning(
    checks: List<PullRequestCheck>,
    verdict: PullRequestCiOutcome?,
    onCiRunning: (List<PullRequestCheck>) -> Unit,
    lastProgressAt: Instant?,
    lastPendingNames: List<String>?,
  ): Pair<Instant, List<String>>? {
    if (verdict != null) return null
    val pending = checks.filter { it.bucket == CheckBucket.PENDING }
    if (pending.isEmpty()) return null
    val names = pending.map(PullRequestCheck::name)
    val due = lastProgressAt == null || Duration.between(lastProgressAt, now()) >= progressInterval
    if (!due && names == lastPendingNames) return null
    onCiRunning(pending)
    return now() to names
  }

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
    val DEFAULT_PROGRESS_INTERVAL: Duration = Duration.ofMinutes(10)
  }
}
