package skillbill.engine.work.timeline.model

import java.time.Instant

public sealed interface WorkActivityResult {
  public data class Listed(val groups: List<WorkActivityGroup>) : WorkActivityResult

  public data object NotFound : WorkActivityResult

  public data class Unavailable(val reason: String) : WorkActivityResult
}

public enum class WorkActivityStatus {
  PENDING,
  RUNNING,
  COMPLETE,
  BLOCKED,
  INTERRUPTED,
  ;

  public val isUnfinished: Boolean get() = this == PENDING || this == RUNNING
}

public enum class WorkActivityBlockKind { BLOCKED, PULL_REQUEST_FAILED, AWAITING_DECISION }

public data class WorkActivityGroup(
  val id: String,
  val subtaskId: Int?,
  val name: String,
  val status: WorkActivityStatus,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val phases: List<WorkActivityPhase>,
  val blocks: List<WorkActivityBlock>,
  val before: List<WorkActivityFact> = emptyList(),
  val after: List<WorkActivityFact> = emptyList(),
)

public data class WorkActivityPhase(
  val phaseId: String,
  val attempt: Int,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val agent: String?,
  val model: String?,
  val effort: String?,
  val summary: String?,
  val verdict: String?,
)

public data class WorkActivityBlock(
  val id: String,
  val at: Instant,
  val kind: WorkActivityBlockKind,
  val phaseId: String?,
  val attempt: Int?,
  val reason: String?,
  val needsDecision: Boolean,
)

public data class WorkActivityFact(
  val name: String,
  val detail: String?,
  val at: Instant?,
  val agent: String?,
  val model: String?,
  val effort: String?,
  val summary: String?,
  val running: Boolean,
)
