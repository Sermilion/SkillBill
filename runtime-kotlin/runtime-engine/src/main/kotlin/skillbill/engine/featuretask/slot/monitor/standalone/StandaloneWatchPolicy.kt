package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import java.time.Duration
import java.time.Instant

private const val POLL_INTERVAL_SECONDS: Long = 30
private const val PROGRESS_INTERVAL_SECONDS: Long = 30
private const val REFRESH_GRACE_MINUTES: Long = 10
private const val NO_CHECKS_GRACE_MINUTES: Long = 2
internal val DEFAULT_POLL_INTERVAL: Duration = Duration.ofSeconds(POLL_INTERVAL_SECONDS)
internal val DEFAULT_START_TIMEOUT: Duration = Duration.ofHours(1)
internal val DEFAULT_REFRESH_GRACE: Duration = Duration.ofMinutes(REFRESH_GRACE_MINUTES)
internal val DEFAULT_NO_CHECKS_GRACE: Duration = Duration.ofMinutes(NO_CHECKS_GRACE_MINUTES)
internal val DEFAULT_PROGRESS_INTERVAL: Duration = Duration.ofSeconds(PROGRESS_INTERVAL_SECONDS)
private const val MAX_PROGRESS_CHECKS: Int = 8
private const val MAX_PROGRESS_TEXT_LENGTH: Int = 160

internal fun standaloneVerdictFor(
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

internal class StandaloneWatchProgress(
  private val now: () -> Instant,
  private val interval: Duration,
) {
  private var reportedAt: Instant? = null
  private var reportedNames: List<String>? = null

  fun report(
    url: String,
    reportedChecks: List<PullRequestCheck>,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  ) {
    val pending = reportedChecks.filter { it.bucket == CheckBucket.PENDING }
    val names = pending.map(PullRequestCheck::name)
    val due = reportedAt?.let { Duration.between(it, now()) >= interval } ?: true
    if (!due && names == reportedNames) return
    onCiRunning(
      url,
      pending.take(MAX_PROGRESS_CHECKS).map { it.copy(name = it.name.take(MAX_PROGRESS_TEXT_LENGTH), link = "") },
    )
    reportedAt = now()
    reportedNames = names
  }
}
