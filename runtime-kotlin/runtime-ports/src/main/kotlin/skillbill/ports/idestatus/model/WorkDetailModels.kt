package skillbill.ports.idestatus.model

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.work.model.WorkGoalDependency
import skillbill.ports.work.model.WorkStoreObservation
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.time.Instant

enum class WorkDetailProblemCode(val wireValue: String) {
  ABSENT_DATABASE("absent_database"),
  UNKNOWN_WORK_ITEM("unknown_work_item"),
  AMBIGUOUS_WORK_ITEM("ambiguous_work_item"),
  INVALID_REPOSITORY_IDENTITY("invalid_repository_identity"),
}

enum class WorkDetailList { SUBTASKS, PHASES, VALIDATION, EXECUTIONS, DEPENDENCIES }

data class WorkDetailRequest(
  val id: String,
  val observedAt: Instant? = null,
  val pageSize: Int = WorkPaginationLimits.DEFAULT_PAGE_SIZE,
  val cursor: String? = null,
  val startOffset: Int = 0,
  val list: WorkDetailList? = null,
  val anchor: String? = null,
) {
  init {
    require(id.isNotBlank()) { "id is required." }
  }
}

data class WorkDetailResult(
  val observedAt: Instant,
  val item: WorkDetailItem? = null,
  val problem: WorkDetailProblem? = null,
  val pageSize: Int = WorkPaginationLimits.DEFAULT_PAGE_SIZE,
  val nextCursor: String? = null,
  val observation: WorkStoreObservation? = null,
  val listCursors: Map<WorkDetailList, String> = emptyMap(),
)

data class WorkDetailProblem(
  val code: WorkDetailProblemCode,
  val message: String,
)

data class WorkDetailItem(
  val snapshot: IdeStatusSnapshot,
  val repositoryRoot: String,
  val stepPipeline: List<String>,
  val finishedAt: Instant? = null,
  val finishedAtSource: WorkBoardCompletionSource? = null,
  val goalManifest: DecompositionManifest? = null,
  val goalFinishPipeline: List<String> = emptyList(),
  val runtimeStatus: WorkDetailRuntimeStatus? = null,
  val goalStatus: GoalRunnerStatusProjection? = null,
  val executionHistory: List<WorkExecutionInstance> = emptyList(),
  val pageOffset: Int = 0,
  val pageSize: Int = WorkPaginationLimits.DEFAULT_PAGE_SIZE,
  val hasMore: Boolean = false,
  val moreLists: Set<WorkDetailList> = emptySet(),
  val observation: WorkStoreObservation? = null,
  val liveSection: WorkLiveSection? = null,
  val controlEligible: Boolean = false,
  val requiresAttention: Boolean = false,
  val dependencies: List<WorkGoalDependency> = emptyList(),
  val freshnessDeadline: Instant? = null,
  val displayGroup: IdeStatusDisplayGroup? = null,
  val displayPriority: Int = 5,
  val exactPauseReason: String? = null,
  val databaseFileObservation: DatabaseFileObservation? = null,
  val pauseEligible: Boolean = false,
  val listAnchors: Map<WorkDetailList, String> = emptyMap(),
  val controlLease: GoalRunnerExecutionLease? = null,
)

data class WorkDetailRuntimeStatus(
  val phases: List<WorkDetailPhase>,
  val currentPhase: String?,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val phaseIds: List<String> = phases.map { it.phaseId },
  val totalPhaseCount: Int = phaseIds.size,
)

data class WorkDetailPhase(
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val resolvedAgent: String?,
  val finished: Boolean,
  val executionOrigin: String?,
  val continuationKind: String?,
  val launchedModel: String?,
  val effort: String?,
)

data class WorkExecutionInstance(
  val invocationId: String,
  val executionId: String,
  val lifecycleState: String,
  val startedAt: Instant,
  val updatedAt: Instant,
  val finishedAt: Instant?,
  val result: String?,
  val restoration: ExecutionReasonRestoration? = null,
  val runSequence: String = "",
  val qaOutcome: StandaloneQaOutcome? = null,
  val qaReportText: String? = null,
  val planReport: StandalonePlanReport? = null,
)

enum class ExecutionReasonRestoration { EXACT, RESTORED, MISSING, AMBIGUOUS, BUDGET_EXCEEDED, MISSING_CONTENT }
