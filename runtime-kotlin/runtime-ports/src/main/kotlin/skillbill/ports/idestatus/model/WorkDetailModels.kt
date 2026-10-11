package skillbill.ports.idestatus.model

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.time.Instant

enum class WorkDetailProblemCode(val wireValue: String) {
  ABSENT_DATABASE("absent_database"),
  UNKNOWN_WORK_ITEM("unknown_work_item"),
  AMBIGUOUS_WORK_ITEM("ambiguous_work_item"),
  INVALID_REPOSITORY_IDENTITY("invalid_repository_identity"),
}

data class WorkDetailRequest(
  val id: String,
  val observedAt: Instant? = null,
  val pageSize: Int = WorkPaginationLimits.DEFAULT_PAGE_SIZE,
  val cursor: String? = null,
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
)

data class WorkDetailRuntimeStatus(
  val phases: List<WorkDetailPhase>,
  val currentPhase: String?,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
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
)
