package skillbill.engine.featuretask.slot.monitor

import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.time.Duration
import java.time.Instant

internal class CheckRegistrationGrace(private val duration: Duration) {
  private var first: Pair<PullRequestIdentity.Found, Instant>? = null

  fun clear() {
    first = null
  }

  fun ready(
    identity: PullRequestIdentity.Found,
    now: Instant,
  ): Boolean {
    if (identity.headSha.isNullOrBlank()) return false
    val previous = first
    if (previous == null || previous.first.number != identity.number || previous.first.headSha != identity.headSha) {
      first = identity to now
      return false
    }
    return Duration.between(previous.second, now) >= duration
  }
}
