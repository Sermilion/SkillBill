package skillbill.ports.idestatus.model

import java.time.Duration
import java.time.Instant

enum class WorkBoardWindow(val wireValue: String, val duration: Duration) {
  SIX_HOURS("6h", Duration.ofHours(6)),
  TWENTY_FOUR_HOURS("24h", Duration.ofHours(24)),
  SEVEN_DAYS("7d", Duration.ofDays(7)),
}

enum class WorkBoardDiagnosticReason(val wireValue: String) {
  MISSING_REPOSITORY_IDENTITY("missing_repository_identity"),
  INVALID_REPOSITORY_IDENTITY("invalid_repository_identity"),
  MISSING_COMPLETION_TIME("missing_completion_time"),
}

enum class WorkBoardCompletionSource(val wireValue: String) {
  STANDALONE_FINISHED_AT("standalone_finished_at"),
  WORKFLOW_COMPLETION("workflow_completion"),
  GOAL_COMPLETION("goal_completion"),
  GOAL_STATE_ENTRY("goal_state_entry"),
  ESTIMATED_GOAL_STATE_ENTRY("estimated_goal_state_entry"),
}

data class WorkBoardRequest(
  val finishedWithin: WorkBoardWindow = WorkBoardWindow.TWENTY_FOUR_HOURS,
  val observedAt: Instant? = null,
)

data class WorkBoardItem(
  val snapshot: IdeStatusSnapshot,
  val repositoryRoot: String,
  val finishedAt: Instant? = null,
  val finishedAtSource: WorkBoardCompletionSource? = null,
)

data class WorkBoardDiagnostic(
  val reason: WorkBoardDiagnosticReason,
  val repositoryIdentity: String?,
  val workflowId: String? = null,
  val invocationId: String? = null,
)

data class WorkBoardResult(
  val observedAt: Instant,
  val finishedWithin: WorkBoardWindow,
  val items: List<WorkBoardItem>,
  val diagnostics: List<WorkBoardDiagnostic> = emptyList(),
  val absentDatabase: Boolean = false,
)
