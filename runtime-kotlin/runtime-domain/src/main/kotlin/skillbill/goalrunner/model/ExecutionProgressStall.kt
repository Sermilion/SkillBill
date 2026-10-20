package skillbill.goalrunner.model

import java.time.Duration
import java.time.Instant

const val EXECUTION_STALL_THRESHOLD_MINUTES: Long = 20

val EXECUTION_STALL_THRESHOLD: Duration = Duration.ofMinutes(EXECUTION_STALL_THRESHOLD_MINUTES)

data class ExecutionProgressStall(
  val lastProgressAt: Instant,
  val observedAt: Instant,
) {
  val idleFor: Duration get() = Duration.between(lastProgressAt, observedAt)

  fun describe(): String = "no progress for ${compactDuration(idleFor)}"

  companion object {
    fun detect(
      liveness: ExecutionLiveness,
      lastProgressAt: Instant?,
      observedAt: Instant,
      threshold: Duration = EXECUTION_STALL_THRESHOLD,
    ): ExecutionProgressStall? =
      lastProgressAt
        ?.takeIf { liveness == ExecutionLiveness.LIVE && Duration.between(it, observedAt) >= threshold }
        ?.let { ExecutionProgressStall(lastProgressAt = it, observedAt = observedAt) }

    private const val MINUTES_PER_HOUR = 60L

    private fun compactDuration(duration: Duration): String {
      val totalMinutes = duration.toMinutes().coerceAtLeast(0)
      val hours = totalMinutes / MINUTES_PER_HOUR
      val minutes = totalMinutes % MINUTES_PER_HOUR
      return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
  }
}
