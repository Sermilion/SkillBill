package skillbill.engine.work

import skillbill.ports.idestatus.model.IdeStatusFreshness
import java.time.Duration
import java.time.Instant

object IdeStatusFreshnessClassifier {
  private const val FRESH_WINDOW_MINUTES = 30L
  val FRESH_WINDOW: Duration = Duration.ofMinutes(FRESH_WINDOW_MINUTES)

  fun classify(
    updatedAt: Instant?,
    observedAt: Instant,
  ): IdeStatusFreshness {
    if (updatedAt == null) return IdeStatusFreshness.UNKNOWN
    val age = Duration.between(updatedAt, observedAt)
    if (age.isNegative) return IdeStatusFreshness.UNKNOWN
    return if (age <= FRESH_WINDOW) IdeStatusFreshness.FRESH else IdeStatusFreshness.STALE
  }
}
