package skillbill.engine.goalrunner.planning.model

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

public enum class GoalPlanningAttemptOutcome(internal val wireValue: String) {
  IN_FLIGHT("in_flight"),
  FAILED("failed"),
  SUCCEEDED("succeeded"),
  ;

  internal companion object {
    internal fun fromWire(value: String): GoalPlanningAttemptOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

public data class GoalPlanningLogRequest(
  public val issueKey: String,
  public val repoRoot: Path? = null,
  public val subtaskId: Int? = null,
  public val failuresOnly: Boolean = false,
)

public data class GoalPlanningLogAttempt(
  public val phaseId: String,
  public val subtaskId: Int,
  public val attempt: Int,
  public val startedAt: Instant?,
  public val finishedAt: Instant?,
  public val outcome: GoalPlanningAttemptOutcome,
  public val rule: String? = null,
  public val reason: String? = null,
  public val agentId: String? = null,
  public val rejectedOutputIdentity: String? = null,
  public val rejectedOutputBytes: Long? = null,
) {
  public val durationMs: Long?
    get() =
      startedAt?.let { start ->
        finishedAt?.let { end ->
          if (end.isBefore(start)) null else Duration.between(start, end).toMillis()
        }
      }

  public val timestampsInconsistent: Boolean
    get() = startedAt != null && finishedAt != null && finishedAt.isBefore(startedAt)

  public val inFlight: Boolean get() = startedAt != null && finishedAt == null
}

public data class GoalPlanningLog(
  public val issueKey: String,
  public val parentWorkflowId: String?,
  public val attempts: List<GoalPlanningLogAttempt> = emptyList(),
) {
  public val totalAttempts: Int get() = attempts.size
  public val failedAttempts: Int get() = attempts.count { it.outcome == GoalPlanningAttemptOutcome.FAILED }
  public val succeededAttempts: Int get() = attempts.count { it.outcome == GoalPlanningAttemptOutcome.SUCCEEDED }

  public val totalPlanningMs: Long get() = attempts.mapNotNull(GoalPlanningLogAttempt::durationMs).sum()

  public val firstAttemptFailures: Int
    get() = attempts.count { it.attempt == 1 && it.outcome == GoalPlanningAttemptOutcome.FAILED }

  public val phasesObserved: Int get() = attempts.map { it.phaseId }.distinct().size
}
