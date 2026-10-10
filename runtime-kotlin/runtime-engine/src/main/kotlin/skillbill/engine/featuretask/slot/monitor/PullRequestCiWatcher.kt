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
  private val watchTimeout: Duration = DEFAULT_WATCH_TIMEOUT,
  private val noChecksGrace: Duration = DEFAULT_NO_CHECKS_GRACE,
) {
  fun watch(
    repoRoot: Path,
    branch: String,
  ): PullRequestCiOutcome =
    when (val identity = identityLookup.lookup(repoRoot, branch)) {
      is PullRequestIdentity.Found -> poll(repoRoot, identity.number)
      PullRequestIdentity.Absent -> PullRequestCiOutcome.NoPullRequest
      is PullRequestIdentity.Unavailable -> PullRequestCiOutcome.Unavailable(identity.reason)
    }

  private fun poll(
    repoRoot: Path,
    prNumber: Int,
  ): PullRequestCiOutcome {
    val startedAt = now()
    var noChecksSince: Instant? = null
    var pendingNames: List<String> = emptyList()
    while (true) {
      val current = now()
      val settled =
        when (val checks = checksLookup.lookup(repoRoot, prNumber)) {
          is PullRequestChecks.Unavailable -> PullRequestCiOutcome.Unavailable(checks.reason)
          PullRequestChecks.NoChecks -> {
            val since = noChecksSince ?: current
            noChecksSince = since
            pendingNames = emptyList()
            PullRequestCiOutcome.NoCiConfigured.takeIf { Duration.between(since, current) >= noChecksGrace }
          }
          is PullRequestChecks.Reported -> {
            noChecksSince = null
            pendingNames = checks.checks.filter { it.bucket == CheckBucket.PENDING }.map(PullRequestCheck::name)
            verdictFor(checks.checks)
          }
        }
      if (settled != null) return settled
      if (Duration.between(startedAt, now()) >= watchTimeout) {
        return PullRequestCiOutcome.Blocked(timeoutReason(pendingNames))
      }
      sleep(pollInterval)
    }
  }

  private fun verdictFor(checks: List<PullRequestCheck>): PullRequestCiOutcome? {
    val failing = checks.filter { it.bucket == CheckBucket.FAIL || it.bucket == CheckBucket.CANCEL }
    return when {
      failing.isNotEmpty() -> PullRequestCiOutcome.Failed(failing)
      checks.any { it.bucket == CheckBucket.PENDING } -> null
      else -> PullRequestCiOutcome.Passed
    }
  }

  private fun timeoutReason(pendingNames: List<String>): String {
    val pending =
      pendingNames.takeIf(List<String>::isNotEmpty)?.joinToString(", ") ?: "no checks reported yet"
    return "CI did not finish within ${watchTimeout.toMinutes()} minutes; still pending: $pending."
  }

  private companion object {
    val DEFAULT_POLL_INTERVAL: Duration = Duration.ofSeconds(30)
    val DEFAULT_WATCH_TIMEOUT: Duration = Duration.ofMinutes(30)
    val DEFAULT_NO_CHECKS_GRACE: Duration = Duration.ofMinutes(3)
  }
}
