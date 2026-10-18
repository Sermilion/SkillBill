package skillbill.ports.idestatus.model

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.work.model.WorkStoreObservation
import java.time.Duration
import java.time.Instant

private const val SHORT_RETENTION_HOURS = 6L
private const val DEFAULT_RETENTION_HOURS = 24L
private const val LONG_RETENTION_DAYS = 7L

enum class WorkBoardWindow(val wireValue: String, val duration: Duration) {
  SIX_HOURS("6h", Duration.ofHours(SHORT_RETENTION_HOURS)),
  TWENTY_FOUR_HOURS("24h", Duration.ofHours(DEFAULT_RETENTION_HOURS)),
  SEVEN_DAYS("7d", Duration.ofDays(LONG_RETENTION_DAYS)),
}

enum class WorkBoardDiagnosticReason(val wireValue: String) {
  MISSING_REPOSITORY_IDENTITY("missing_repository_identity"),
  INVALID_REPOSITORY_IDENTITY("invalid_repository_identity"),
  MISSING_COMPLETION_TIME("missing_completion_time"),
  INCOMPATIBLE_RECORD("incompatible_record"),
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
  val pageSize: Int = WorkPaginationLimits.DEFAULT_PAGE_SIZE,
  val cursor: String? = null,
)

enum class WorkLiveSection { NEEDS_YOU, GOALS, PHASES }

data class WorkBoardItem(
  val snapshot: IdeStatusSnapshot,
  val repositoryRoot: String,
  val finishedAt: Instant? = null,
  val finishedAtSource: WorkBoardCompletionSource? = null,
  val controlLease: GoalRunnerExecutionLease? = null,
  val observation: WorkStoreObservation? = null,
  val freshnessDeadline: Instant? = null,
  val liveSection: WorkLiveSection? = null,
  val controlEligible: Boolean = false,
  val requiresAttention: Boolean = false,
  val displayGroup: IdeStatusDisplayGroup? = null,
  val displayPriority: Int = 5,
  val exactPauseReason: String? = null,
  val databaseFileObservation: DatabaseFileObservation? = null,
  val pauseEligible: Boolean = false,
  val running: Boolean = false,
  val recentlyFinishedPhase: Boolean = false,
  val doneWithinDay: Boolean = false,
  val membershipDeadline: Instant? = null,
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
  val pageSize: Int = WorkPaginationLimits.DEFAULT_PAGE_SIZE,
  val nextCursor: String? = null,
  val observation: WorkStoreObservation? = null,
  val candidatesExamined: Int = 0,
)
