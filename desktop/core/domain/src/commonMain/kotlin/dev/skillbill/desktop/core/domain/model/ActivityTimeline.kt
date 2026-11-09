package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

sealed interface ActivityResult {
  data class Listed(val groups: List<ActivityGroup>) : ActivityResult
  data object Loading : ActivityResult
  data object NotFound : ActivityResult
  data class Unavailable(val problem: ReadProblem) : ActivityResult
}

enum class ActivityStatus { PENDING, RUNNING, COMPLETE, BLOCKED, INTERRUPTED }
enum class ActivityBlockKind { BLOCKED, PULL_REQUEST_FAILED, AWAITING_DECISION }

enum class ActivityGroupKind { SUBTASK, GOAL_FINISH, GENERAL_BLOCKS }

data class ActivityGroup(
  val id: String,
  val subtaskId: Int?,
  val name: String,
  val status: ActivityStatus,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val phases: List<ActivityPhase>,
  val blocks: List<ActivityBlock>,
  val before: List<ActivityFact> = emptyList(),
  val after: List<ActivityFact> = emptyList(),
  val kind: ActivityGroupKind = ActivityGroupKind.SUBTASK,
  val phaseEvents: List<ActivityPhaseEvent> = emptyList(),
)

data class ActivityPhase(
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

data class ActivityBlock(
  val id: String,
  val at: Instant,
  val kind: ActivityBlockKind,
  val phaseId: String?,
  val attempt: Int?,
  val reason: String?,
  val needsDecision: Boolean,
)

data class ActivityFact(
  val name: String,
  val detail: String?,
  val at: Instant?,
  val agent: String?,
  val model: String?,
  val effort: String?,
  val summary: String?,
  val running: Boolean,
)

data class ActivityPhaseEvent(val phaseId: String?, val at: Instant, val label: String, val blocked: Boolean)
