package skillbill.ports.idestatus.model

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.work.model.WorkGoalDependency
import skillbill.ports.work.model.WorkStoreObservation
import skillbill.review.model.ReviewFindingAction
import skillbill.review.model.ReviewFindingStatus
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunPreview
import java.time.Instant

enum class WorkDetailProblemCode(val wireValue: String) {
  ABSENT_DATABASE("absent_database"),
  UNKNOWN_WORK_ITEM("unknown_work_item"),
  AMBIGUOUS_WORK_ITEM("ambiguous_work_item"),
  INVALID_REPOSITORY_IDENTITY("invalid_repository_identity"),
}

enum class WorkDetailList { SUBTASKS, PHASES, VALIDATION, EXECUTIONS, DEPENDENCIES, PLAN_OUTPUT }

data class WorkDetailRequest(
  val id: String,
  val observedAt: Instant? = null,
  val pageSize: Int = WorkPaginationLimits.DEFAULT_PAGE_SIZE,
  val cursor: String? = null,
  val startOffset: Int = 0,
  val list: WorkDetailList? = null,
  val anchor: String? = null,
  val includeExtendedLists: Boolean = true,
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
  val recoveryWorkId: String,
  val repositoryRoot: String,
  val stepPipeline: List<String>,
  val finishedAt: Instant? = null,
  val finishedAtSource: WorkBoardCompletionSource? = null,
  val goalManifest: DecompositionManifest? = null,
  val goalSpecBundle: StandalonePlanReport? = null,
  val goalFinishPipeline: List<String> = emptyList(),
  val runtimeStatus: WorkDetailRuntimeStatus? = null,
  val goalStatus: GoalRunnerStatusProjection? = null,
  val executionHistory: List<WorkExecutionInstance> = emptyList(),
  val selectedExecution: WorkExecutionInstance? = null,
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
  val running: Boolean = false,
  val listAnchors: Map<WorkDetailList, String> = emptyMap(),
  val controlLease: GoalRunnerExecutionLease? = null,
  val incident: WorkIncidentProjection? = null,
  val incidentHistory: List<WorkIncidentProjection> = emptyList(),
  val changeBaseline: WorkChangeBaseline? = null,
  val goalWorktree: GoalWorktreeView? = null,
  val validationBranchRuns: List<WorkValidationBranchRun> = emptyList(),
  val selectedValidationResult: String? = null,
  val planActivity: List<StandalonePlanActivity> = emptyList(),
  val planOutputLines: List<StandalonePhaseOutputLine> = emptyList(),
  val planOutputTail: List<String> = emptyList(),
  val planOutputUpdatedAt: Instant? = null,
  val planSubtaskLatestLines: Map<Int, String> = emptyMap(),
  val standalonePlanProgress: StandalonePlanProgress? = null,
  val verifyRun: VerifyRunDetails? = null,
  val build: WorkBuildView? = null,
  val qaPreview: QaRunPreview? = null,
  val qaPreviewSubtaskId: Int? = null,
)

data class WorkValidationBranchRun(
  val invocationId: String,
  val runSequence: String,
  val lifecycleState: String,
  val startedAt: Instant,
  val finishedAt: Instant?,
  val activeDurationMs: Long?,
  val launchAgentId: String?,
  val currentActivity: String?,
  val result: String?,
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
  val briefingText: String? = null,
)

data class WorkQaFinding(val id: String, val title: String)

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
  val reviewReport: StandaloneReviewReport? = null,
  val qaChecklist: StandaloneQaChecklist? = null,
  val qaFindings: List<WorkQaFinding> = emptyList(),
  val qaDetails: QaRunDetails? = null,
  val liveChecks: LiveChecksAvailability? = null,
  val validationOutcome: StandaloneValidationOutcome? = null,
  val validationChecks: List<WorkValidationCheck> = emptyList(),
)

data class WorkValidationCheck(
  val checkId: String,
  val name: String,
  val command: String,
  val workingDir: String,
  val attempts: List<WorkValidationCheckAttempt>,
)

data class WorkValidationCheckAttempt(
  val number: Int,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val outcome: ValidationCheckOutcome?,
  val exitCode: Int?,
  val note: String?,
)

data class StandaloneReviewReport(
  val verdict: String?,
  val summary: String?,
  val findings: List<StandaloneReviewReportFinding>,
  val lanes: List<String> = emptyList(),
  val findingActions: List<ReviewFindingAction> = emptyList(),
)

data class StandaloneReviewReportFinding(
  val ref: String,
  val severity: String,
  val confidence: String?,
  val specialist: String?,
  val location: String?,
  val description: String,
  val status: ReviewFindingStatus = ReviewFindingStatus.OPEN,
)

enum class ExecutionReasonRestoration { EXACT, RESTORED, MISSING, AMBIGUOUS, BUDGET_EXCEEDED, MISSING_CONTENT }
