package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import java.time.Duration
import java.time.Instant

internal val DEFAULT_POLL_INTERVAL: Duration = Duration.ofSeconds(30)
internal val DEFAULT_START_TIMEOUT: Duration = Duration.ofHours(1)
internal val DEFAULT_PROGRESS_INTERVAL: Duration = Duration.ofSeconds(30)
private const val MAX_PROGRESS_CHECKS: Int = 8
private const val MAX_PROGRESS_TEXT_LENGTH: Int = 160

internal data class StandaloneWatchProgress(val at: Instant? = null, val names: List<String>? = null)

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

internal fun reportStandaloneCiRunning(
  targetUrl: String,
  reportedChecks: List<PullRequestCheck>,
  onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  previous: StandaloneWatchProgress,
  now: Instant,
  progressInterval: Duration,
): StandaloneWatchProgress {
  val pending = reportedChecks.filter { it.bucket == CheckBucket.PENDING }
  val names = pending.map(PullRequestCheck::name)
  val due = previous.at == null || Duration.between(previous.at, now) >= progressInterval
  if (!due && names == previous.names) return previous
  onCiRunning(
    targetUrl,
    pending.take(MAX_PROGRESS_CHECKS).map { it.copy(name = it.name.take(MAX_PROGRESS_TEXT_LENGTH), link = "") },
  )
  return StandaloneWatchProgress(now, names)
}
