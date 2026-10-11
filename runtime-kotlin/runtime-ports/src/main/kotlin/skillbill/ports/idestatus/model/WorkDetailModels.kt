package skillbill.ports.idestatus.model

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
) {
  init {
    require(id.isNotBlank()) { "id is required." }
  }
}

data class WorkDetailResult(
  val observedAt: Instant,
  val item: WorkDetailItem? = null,
  val problem: WorkDetailProblem? = null,
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
