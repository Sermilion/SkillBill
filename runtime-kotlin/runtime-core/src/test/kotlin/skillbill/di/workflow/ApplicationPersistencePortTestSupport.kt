package skillbill.di.workflow

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.review.service.ReviewService
import skillbill.application.review.snapshot.HARNESS_ORIGIN_UNAVAILABLE
import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest
import skillbill.application.telemetry.service.TelemetryLevelMutationService
import skillbill.application.telemetry.service.TelemetryService
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.slot.testExecutionPlan
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.error.shellcontent.MissingCompositionLayerError
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.host.concurrency.JvmInterruptSignalPort
import skillbill.infrastructure.workflow.decomposition.FileSystemDecompositionManifestFileStore
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.learnings.model.UpdateLearningRequest
import skillbill.model.EnvironmentContext
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.learning.model.LearningResolution
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.repository.toFileLocation
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.ports.review.model.ReviewRepositoryStatsSnapshot
import skillbill.ports.review.preparation.ReviewAttributionPort
import skillbill.ports.review.preparation.ReviewInputSource
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.review.repository.ReviewRunCompletenessRepository
import skillbill.ports.review.repository.UnavailableReviewRunCompletenessRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryOutboxSettlementResult
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import skillbill.ports.telemetry.model.TelemetryReconciliationResult
import skillbill.ports.telemetry.transport.TelemetryClient
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.WorkflowStatsRepository
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperationsTestBase
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryDeliveryOutcome
import skillbill.telemetry.model.TelemetryDeliveryReport
import skillbill.telemetry.model.TelemetryOpenDocument
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.FeatureTaskWorkflowMode.PROSE
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.lang.Boolean.TYPE
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.assertEquals
import java.lang.Double.TYPE as DoubleTYPE
import java.lang.Long.TYPE as LongTYPE
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator as FeatureTaskRuntimeWireArtifactSchemaValidator

internal fun <T> noopPort(type: Class<T>): T {
  @Suppress("UNCHECKED_CAST")
  return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
    defaultPortReturn(method)
  } as T
}

private val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()
private val FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS.label()
private val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()

private fun defaultPortReturn(method: Method): Any? =
  when {
    method.returnType == Void.TYPE -> null
    List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
    Map::class.java.isAssignableFrom(method.returnType) -> emptyMap<Any, Any>()
    method.returnType == TYPE -> false
    method.returnType == Integer.TYPE -> 0
    method.returnType == LongTYPE -> 0L
    method.returnType == DoubleTYPE -> 0.0
    else -> null
  }

internal fun WorkflowService.openTestFeatureTask(
  kind: WorkflowFamilyKind,
  sessionId: String = "",
  currentStepId: String? = null,
  issueKey: String = "SKILL-120",
): WorkflowOpenResult =
  openFeatureTask(
    WorkflowServiceOpenFeatureTaskArgs(
      executionPlan = testExecutionPlan(),
      kind = kind,
      sessionId = sessionId,
      currentStepId = currentStepId,
      issueKey = issueKey,
      repositoryIdentity = "repo-root-realpath-v1:/test/repository",
      governedSpecPath = ".feature-specs/$issueKey/spec.md",
    ),
  )

internal fun laneReviewService(
  database: FakeDatabaseSessionFactory,
  text: String,
): ReviewService =
  ReviewService(
    EnvironmentContext(
      environment = emptyMap(),
      userHome = Files.createTempDirectory("skillbill-app-lane"),
      stdinText = text,
    ),
    database,
    FakeTelemetrySettingsProvider(enabled = false),
    FakeReviewInputSource,
    FakePlanReviewAttributionPort,
    NoopRuntimeDiagnostics,
    HARNESS_ORIGIN_UNAVAILABLE,
  )

internal fun reviewText(findings: Boolean): String {
  val header =
    """
    Routed to: bill-kmp-code-review
    Review session ID: rvs-lane-app-001
    Review run ID: rvw-lane-app-001
    Detected review scope: unstaged changes
    Detected stack: kmp
    Execution mode: inline
    Specialist reviews: architecture, narrated-only

    ### 2. Risk Register
    """.trimIndent()
  return if (findings) {
    "$header\n- [F-001] Major | High | Repo.kt:12 | Transaction is not rolled back.\n"
  } else {
    "$header\nNo findings.\n"
  }
}

internal class FakeDatabaseSessionFactory(
  private val reviews: ReviewRepository = FakeReviewRepository(),
  private val learnings: LearningRepository = FakeLearningRepository(),
  private val telemetryOutbox: TelemetryOutboxRepository = NoopTelemetryOutboxRepository,
  private val telemetryReconciliation: TelemetryReconciliationRepository = NoopTelemetryReconciliationRepository,
  private val workflows: WorkflowStateRepository = NoopWorkflowStateRepository,
  private val lifecycleTelemetry: LifecycleTelemetryRepository = noopPort(LifecycleTelemetryRepository::class.java),
) : DatabaseSessionFactory {
  val calls = mutableListOf<String>()
  private val dbPath = Path.of("/fake/metrics.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T {
    calls += "read"
    return block(fakeUnitOfWork())
  }

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T {
    calls += "transaction"
    return block(fakeUnitOfWork())
  }

  private fun fakeUnitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@FakeDatabaseSessionFactory.dbPath
      override val reviews: ReviewRepository = this@FakeDatabaseSessionFactory.reviews
      override val learnings: LearningRepository = this@FakeDatabaseSessionFactory.learnings
      override val lifecycleTelemetry: LifecycleTelemetryRepository = this@FakeDatabaseSessionFactory.lifecycleTelemetry
      override val telemetryReconciliation: TelemetryReconciliationRepository =
        this@FakeDatabaseSessionFactory.telemetryReconciliation
      override val telemetryOutbox: TelemetryOutboxRepository = this@FakeDatabaseSessionFactory.telemetryOutbox
      override val workflowStates: WorkflowStateRepository = this@FakeDatabaseSessionFactory.workflows
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

internal class RecordingProjectionLifecycleTelemetryRepository :
  LifecycleTelemetryRepository by noopPort(LifecycleTelemetryRepository::class.java) {
  val projectionMeasurements = mutableListOf<FeatureTaskRuntimeProjectionMeasurement>()
  val sharedEvidenceMeasurements = mutableListOf<FeatureTaskRuntimeSharedEvidenceMeasurement>()

  override fun featureTaskRuntimeProjectionMeasurement(record: FeatureTaskRuntimeProjectionMeasurement) {
    projectionMeasurements += record
  }

  override fun featureTaskRuntimeSharedEvidence(record: FeatureTaskRuntimeSharedEvidenceMeasurement) {
    sharedEvidenceMeasurements += record
  }
}

internal object NoopTelemetryReconciliationRepository : TelemetryReconciliationRepository {
  override fun reconcileStaleSessions(request: TelemetryReconciliationRequest): TelemetryReconciliationResult =
    TelemetryReconciliationResult.Empty
}

internal class RecordingTelemetryReconciliationRepository : TelemetryReconciliationRepository {
  val requests = mutableListOf<TelemetryReconciliationRequest>()
  val levels: List<String> get() = requests.map(TelemetryReconciliationRequest::level)
  val cadenceSeconds: List<Long> get() = requests.map(TelemetryReconciliationRequest::cadenceSeconds)

  override fun reconcileStaleSessions(request: TelemetryReconciliationRequest): TelemetryReconciliationResult {
    requests += request
    return TelemetryReconciliationResult.Empty
  }
}

internal object ThrowingTelemetryReconciliationRepository : TelemetryReconciliationRepository {
  override fun reconcileStaleSessions(request: TelemetryReconciliationRequest): TelemetryReconciliationResult =
    error("SQLITE_BUSY: database is locked")
}

internal fun goalStartedRequest(): GoalStartedRequest =
  GoalStartedRequest(
    issueKey = "SKILL-66",
    featureName = "goal telemetry",
    workflowId = "wf-goal-1",
    subtaskTotal = 4,
    resumed = true,
    startedAt = "2026-06-04T10:00:00Z",
    mode = "runtime",
  )

internal fun goalSubtaskFinishedRequest(): GoalSubtaskFinishedRequest =
  GoalSubtaskFinishedRequest(
    issueKey = "SKILL-66",
    workflowId = "wf-goal-1",
    subtaskId = 2,
    subtaskName = "persistence",
    status = "blocked",
    startedAt = "2026-06-04T10:05:00Z",
    finishedAt = "2026-06-04T10:09:00Z",
    durationMs = 240_000L,
    attemptCount = 3,
    blockedReason = "validation failed",
  )

internal fun goalFinishedRequest(): GoalFinishedRequest =
  GoalFinishedRequest(
    issueKey = "SKILL-66",
    workflowId = "wf-goal-1",
    status = "blocked",
    startedAt = "2026-06-04T10:00:00Z",
    finishedAt = "2026-06-04T10:20:00Z",
    durationMs = 1_200_000L,
    subtasksComplete = 1,
    subtasksBlocked = 1,
    subtasksSkipped = 0,
    mode = "runtime",
  )

internal class RecordingGoalLifecycleTelemetryRepository :
  LifecycleTelemetryRepository by noopPort(LifecycleTelemetryRepository::class.java) {
  val startedRecords = mutableListOf<GoalStartedRecord>()
  val subtaskRecords = mutableListOf<GoalSubtaskFinishedRecord>()
  val finishedRecords = mutableListOf<GoalFinishedRecord>()
  val issueFinishedRecords = mutableListOf<GoalIssueFinishedRecord>()

  override fun goalStarted(
    record: GoalStartedRecord,
    level: String,
  ) {
    startedRecords += record
  }

  override fun goalSubtaskFinished(
    record: GoalSubtaskFinishedRecord,
    level: String,
  ) {
    subtaskRecords += record
  }

  override fun goalFinished(
    record: GoalFinishedRecord,
    level: String,
  ) {
    finishedRecords += record
  }

  override fun goalIssueFinished(
    record: GoalIssueFinishedRecord,
    level: String,
  ) {
    issueFinishedRecords += record
  }
}

internal class FakeGoalStatsRepository(
  private val stats: GoalWorkflowStats,
) : WorkflowStatsRepository {
  override fun featureVerifyStats(): FeatureVerifyWorkflowStats = error("Unexpected featureVerifyStats")

  override fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats = error("Unexpected featureTaskRuntimeStats")

  override fun goalStats(): GoalWorkflowStats = stats
}

internal class FakeGoalStatsReviewRepository(
  private val stats: GoalWorkflowStats,
) : ReviewRepository, ReviewRunCompletenessRepository by UnavailableReviewRunCompletenessRepository {
  override fun saveImportedReview(
    review: ImportedReview,
    sourcePath: String?,
  ) = error("Unexpected saveImportedReview")

  override fun markOrchestrated(runId: String) = error("Unexpected markOrchestrated")

  override fun updateReviewFinishedTelemetryState(
    runId: String,
    enabled: Boolean,
    level: String,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? = error("Unexpected updateReviewFinishedTelemetryState")

  override fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? = error("Unexpected recordFeedback")

  override fun fetchNumberedFindings(runId: String): List<NumberedFinding> = error("Unexpected fetchNumberedFindings")

  override fun findingExists(
    runId: String,
    findingId: String,
  ): Boolean = error("Unexpected findingExists")

  override fun latestRejectedLearningSourceOutcome(
    runId: String,
    findingId: String,
  ): RejectedLearningSourceOutcome? = error("Unexpected latestRejectedLearningSourceOutcome")

  override fun reviewStats(runId: String?): ReviewRepositoryStatsSnapshot = error("Unexpected reviewStats")

  override fun saveAccounting(record: ReviewAccountingRecord) = Unit

  override fun loadAccounting(reviewId: String): ReviewAccountingRecord? = null

  override fun featureVerifyStats(): FeatureVerifyWorkflowStats = error("Unexpected featureVerifyStats")

  override fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats = error("Unexpected featureTaskRuntimeStats")

  override fun goalStats(): GoalWorkflowStats = stats
}

internal object FakeReviewInputSource : ReviewInputSource {
  override fun readInput(
    inputPath: String,
    stdinText: String?,
  ): Pair<String, String?> = (stdinText ?: "") to null
}

internal class FakeLearningRepository(
  private val records: MutableMap<Int, LearningRecord> = mutableMapOf(),
) : LearningRepository {
  val addedRequests = mutableListOf<CreateLearningRequest>()

  override fun list(status: String): List<LearningRecord> =
    records.values.filter { status == "all" || it.status == status }.sortedBy { it.id }

  override fun get(id: Int): LearningRecord = records.getValue(id)

  override fun resolve(
    repoScopeKey: String?,
    skillName: String?,
  ): LearningResolution =
    LearningResolution(repoScopeKey = repoScopeKey, skillName = skillName, records = list(status = "active"))

  override fun saveSessionLearnings(
    reviewSessionId: String,
    learningsJson: String,
  ) = Unit

  override fun add(
    request: CreateLearningRequest,
    sourceValidation: LearningSourceValidation,
  ): Int {
    addedRequests += request
    val id = (records.keys.maxOrNull() ?: 0) + 1
    records[id] =
      learningRecord(id = id, title = request.title).copy(
        scope = request.scope.wireName,
        scopeKey = request.scopeKey,
        ruleText = request.ruleText,
        rationale = request.rationale,
        sourceReviewRunId = sourceValidation.reviewRunId,
        sourceFindingId = sourceValidation.findingId,
      )
    return id
  }

  override fun edit(request: UpdateLearningRequest): LearningRecord =
    records.getValue(request.learningId).let { current ->
      current.copy(
        scope = request.scope?.wireName ?: current.scope,
        scopeKey = request.scopeKey ?: current.scopeKey,
        title = request.title ?: current.title,
        ruleText = request.ruleText ?: current.ruleText,
        rationale = request.rationale ?: current.rationale,
      ).also { records[request.learningId] = it }
    }

  override fun setStatus(
    id: Int,
    status: String,
  ): LearningRecord = records.getValue(id).copy(status = status).also { records[id] = it }

  override fun delete(id: Int) {
    records.remove(id)
  }
}

internal class FakeReviewRepository(
  private val numberedFindings: List<NumberedFinding> = emptyList(),
  private val sourceFindingExists: Boolean = false,
  private val rejectedLearningSourceOutcome: RejectedLearningSourceOutcome? = null,
) : ReviewRepository, ReviewRunCompletenessRepository by UnavailableReviewRunCompletenessRepository {
  val feedbackRequests = mutableListOf<FeedbackRequest>()
  val learningSourceLookups = mutableListOf<String>()
  val savedReviews = mutableListOf<ImportedReview>()
  val terminalStateWrites = mutableListOf<Pair<String, String?>>()

  override fun saveImportedReview(
    review: ImportedReview,
    sourcePath: String?,
  ) {
    savedReviews += review
  }

  override fun ensureTerminalReviewState(
    runId: String,
    executionMode: String?,
  ) {
    terminalStateWrites += runId to executionMode
  }

  override fun markOrchestrated(runId: String) = error("Unexpected markOrchestrated")

  override fun updateReviewFinishedTelemetryState(
    runId: String,
    enabled: Boolean,
    level: String,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? = null

  override fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? {
    feedbackRequests += request
    return null
  }

  override fun fetchNumberedFindings(runId: String): List<NumberedFinding> = numberedFindings

  override fun findingExists(
    runId: String,
    findingId: String,
  ): Boolean {
    learningSourceLookups += "$runId:$findingId"
    return sourceFindingExists
  }

  override fun latestRejectedLearningSourceOutcome(
    runId: String,
    findingId: String,
  ): RejectedLearningSourceOutcome? = rejectedLearningSourceOutcome

  override fun reviewStats(runId: String?): ReviewRepositoryStatsSnapshot = error("Unexpected reviewStats")

  override fun saveAccounting(record: ReviewAccountingRecord) = Unit

  override fun loadAccounting(reviewId: String): ReviewAccountingRecord? = null

  override fun featureVerifyStats(): FeatureVerifyWorkflowStats = error("Unexpected featureVerifyStats")

  override fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats = error("Unexpected featureTaskRuntimeStats")

  override fun goalStats(): GoalWorkflowStats = error("Unexpected goalStats")
}

internal object FakePlanReviewAttributionPort : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> = emptyMap()

  override fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan =
    ReviewLaunchPlan(
      routedPackSlug = routedPackSlug,
      lanes =
        listOf(
          ReviewLaunchLane(
            skillName = "bill-kmp-code-review-architecture",
            packSlug = "kmp",
            area = "architecture",
            depth = 0,
            originLayerChain = listOf("kmp"),
            required = true,
            addOns = emptyList(),
            orderIndex = 0,
            inclusionReason = "routed-pack override",
          ),
        ),
    )
}

internal object ThrowingPlanReviewAttributionPort : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> = emptyMap()

  override fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan =
    throw MissingCompositionLayerError("Baseline layer 'kotlin' is not installed.")
}

internal object NoopTelemetryOutboxRepository : TelemetryOutboxRepository {
  override fun enqueue(
    event: TelemetryOutboxEvent,
    payloadJson: String,
  ): Long = error("Unexpected enqueue")

  override fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord> = emptyList()

  override fun pendingCount(): Int = 0

  override fun blockedCount(attemptBudget: Int): Int = 0

  override fun latestError(): String? = null

  override fun lastSyncedAt(): String? = null

  override fun markSynced(
    eventIds: List<Long>,
    claimToken: String,
  ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

  override fun markFailed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

  override fun markUnconfirmed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

  override fun clear(): Int = 0
}

internal class InMemoryTelemetryOutboxRepository(
  private val rows: MutableList<TelemetryOutboxRecord> = mutableListOf(),
) : TelemetryOutboxRepository {
  val enqueuedEventNames = mutableListOf<String>()
  private val claimTokens = mutableMapOf<Long, String>()

  override fun enqueue(
    event: TelemetryOutboxEvent,
    payloadJson: String,
  ): Long {
    val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
    enqueuedEventNames += event.wireValue
    rows +=
      TelemetryOutboxRecord(
        id = id,
        eventName = event.wireValue,
        payloadJson = payloadJson,
        createdAt = "2026-04-24 00:00:00",
        syncedAt = null,
        lastError = "",
      )
    return id
  }

  fun listPending(limit: Int? = null): List<TelemetryOutboxRecord> =
    rows.filter { it.syncedAt == null }.let { pending ->
      if (limit == null) pending else pending.take(limit)
    }

  override fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord> {
    val claimed =
      rows.filter { it.syncedAt == null && it.deliveryAttempts < request.attemptBudget }.take(request.limit)
    claimed.forEach { claimTokens[it.id] = request.claimToken }
    return claimed
  }

  override fun pendingCount(): Int = rows.count { it.syncedAt == null }

  override fun blockedCount(attemptBudget: Int): Int =
    rows.count { it.syncedAt == null && it.deliveryAttempts >= attemptBudget }

  override fun latestError(): String? = rows.lastOrNull { it.syncedAt == null && it.lastError.isNotBlank() }?.lastError

  override fun lastSyncedAt(): String? = rows.mapNotNull { it.syncedAt }.maxOrNull()

  override fun markSynced(
    eventIds: List<Long>,
    claimToken: String,
  ): TelemetryOutboxSettlementResult {
    var updated = 0
    rows.replaceAll { row ->
      if (row.id in eventIds && claimTokens[row.id] == claimToken && row.syncedAt == null) {
        updated++
        claimTokens.remove(row.id)
        row.copy(syncedAt = "2026-04-24 00:00:01", lastError = "")
      } else {
        row
      }
    }
    return TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = updated)
  }

  override fun markFailed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult {
    var updated = 0
    rows.replaceAll { row ->
      if (row.id in eventIds && claimTokens[row.id] == claimToken && row.syncedAt == null) {
        updated++
        claimTokens.remove(row.id)
        row.copy(lastError = lastError, deliveryAttempts = row.deliveryAttempts + 1)
      } else {
        row
      }
    }
    return TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = updated)
  }

  override fun markUnconfirmed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult {
    var updated = 0
    rows.replaceAll { row ->
      if (row.id in eventIds && claimTokens[row.id] == claimToken && row.syncedAt == null) {
        updated++
        claimTokens.remove(row.id)
        row.copy(lastError = lastError)
      } else {
        row
      }
    }
    return TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = updated)
  }

  override fun clear(): Int {
    val count = rows.size
    rows.clear()
    return count
  }
}

internal class FakeTelemetrySettingsProvider(
  private val enabled: Boolean,
) : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings =
    TelemetrySettings(
      configPath = Path.of("/fake/config.json").toFileLocation(),
      level = if (enabled) "anonymous" else "off",
      enabled = enabled,
      installId = if (enabled) "fake-install-id" else "",
      proxyUrl = if (enabled) "https://telemetry.example.dev/ingest" else "",
      customProxyUrl = if (enabled) "https://telemetry.example.dev/ingest" else null,
      batchSize = 50,
    )
}

internal object FakeTelemetryConfigStore : TelemetryConfigStore {
  override fun stateDir(): Path = Path.of("/fake")

  override fun configPath(): Path = Path.of("/fake/config.json")

  override fun read(): TelemetryConfigDocument? = null

  override fun ensure(): TelemetryConfigDocument = TelemetryConfigDocument(TelemetryOpenDocument.from(emptyMap()))

  override fun write(document: TelemetryConfigDocument) = Unit
}

internal class FakeTelemetryClient : TelemetryClient {
  val sentBatchIds = mutableListOf<List<Long>>()

  override fun sendBatch(
    settings: TelemetrySettings,
    rows: List<TelemetryOutboxRecord>,
  ): TelemetryDeliveryReport {
    sentBatchIds += rows.map { it.id }
    return TelemetryDeliveryReport(TelemetryDeliveryOutcome.ACCEPTED)
  }

  override fun fetchProxyCapabilities(settings: TelemetrySettings): TelemetryProxyCapabilities =
    error("Unexpected fetchProxyCapabilities")

  override fun fetchRemoteStats(
    settings: TelemetrySettings,
    request: RemoteStatsRequest,
  ): TelemetryRemoteStatsResult = error("Unexpected fetchRemoteStats")
}

internal object NoopWorkflowStateRepository : WorkflowStateRepositoryDefaults()

internal fun createDecompositionWorkflow(
  service: WorkflowService,
  parentSpec: Path,
  subtaskSpec: Path,
): String = createDecompositionWorkflow(service, parentSpec, subtaskSpec, null)

internal fun blockedGoalChildRetryFixture(): BlockedGoalChildRetryFixture {
  val tempDir = Files.createTempDirectory("skillbill-goal-child-retry")
  val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
  val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
  writeSpecs(parentSpec, subtaskSpec)
  val database = FakeDatabaseSessionFactory(workflows = InMemoryWorkflowStateRepository())
  val service = testWorkflowService(database)
  val parentWorkflowId = createDecompositionWorkflow(service, parentSpec, subtaskSpec)
  markDecompositionSubtaskBlocked(service, parentWorkflowId, subtaskSpec)
  val childWorkflowId =
    (
      service.openTestFeatureTask(
        WorkflowFamilyKind.TASK_RUNTIME,
        sessionId = "ftr-goal-child",
        issueKey = "SKILL-51",
      ) as WorkflowOpenResult.Ok
    ).workflowId
  service.update(
    WorkflowFamilyKind.TASK_RUNTIME,
    WorkflowUpdateRequest(
      workflowId = childWorkflowId,
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
      currentStepId = "preplan",
      stepUpdates = null,
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to
              FeatureTaskRuntimeGoalContinuationArtifact(
                issueKey = "SKILL-51",
                subtaskId = 1,
                suppressPr = true,
                goalBranch = "feat/SKILL-51-demo",
                parentWorkflowId = parentWorkflowId,
                codeReviewMode = CodeReviewExecutionMode.INLINE,
              ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
          ),
        ),
    ),
  )
  testPhaseRecorder(database).recordRuntimePhase(
    childWorkflowId,
    phaseId = "implement",
    status = "blocked",
    finished = false,
    blockedReason = "native adapter unavailable",
  )
  return BlockedGoalChildRetryFixture(
    service = service,
    parentWorkflowId = parentWorkflowId,
    childWorkflowId = childWorkflowId,
    manifestPath = parentSpec.parent.resolve("decomposition-manifest.yaml"),
  )
}

internal data class BlockedGoalChildRetryFixture(
  val service: WorkflowService,
  val parentWorkflowId: String,
  val childWorkflowId: String,
  val manifestPath: Path,
)

internal fun createDecompositionWorkflow(
  service: WorkflowService,
  parentSpec: Path,
  subtaskOne: Path,
  subtaskTwo: Path?,
  executionModel: String = "same_branch_commit_per_subtask",
): String {
  val opened =
    service.openTestFeatureTask(
      WorkflowFamilyKind.TASK_RUNTIME,
      sessionId = "ftr-001",
      issueKey = "SKILL-51",
    )
      as WorkflowOpenResult.Ok
  val workflowId = opened.workflowId
  service.update(
    WorkflowFamilyKind.TASK_RUNTIME,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
      currentStepId = "plan",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
          ),
        ),
      artifactsPatch = decompositionPlanPatch(parentSpec, subtaskOne, subtaskTwo, executionModel),
      planningResult =
        decompositionPlanningResultFromPatch(
          decompositionPlanPatch(parentSpec, subtaskOne, subtaskTwo, executionModel),
        ),
    ),
  )
  return workflowId
}

internal fun markDecompositionSubtaskBlocked(
  service: WorkflowService,
  workflowId: String,
  subtaskSpec: Path,
) {
  service.update(
    WorkflowFamilyKind.TASK_RUNTIME,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = WorkflowStatus.BLOCKED.wireValue,
      currentStepId = "validate",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "validate", "status" to "blocked", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
            FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to completedPhaseRecords("plan", "audit"),
            "validation_result" to mapOf("passed" to false),
            "blocked_reason" to "Validation failed.",
          ),
        ),
    ),
  )
}

internal fun markDecompositionSubtaskSkipped(
  service: WorkflowService,
  workflowId: String,
  subtaskSpec: Path,
) {
  service.update(
    WorkflowFamilyKind.TASK_RUNTIME,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
      currentStepId = "pr",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "pr", "status" to "skipped", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
            FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to completedPhaseRecords("implement", "commit_push"),
          ),
        ),
    ),
  )
}

internal fun markDecompositionSubtaskComplete(
  service: WorkflowService,
  workflowId: String,
  subtaskSpec: Path,
) {
  service.update(
    WorkflowFamilyKind.TASK_RUNTIME,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = WorkflowStatus.COMPLETED.wireValue,
      currentStepId = "pr",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "pr", "status" to "completed", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
            FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to completedPhaseRecords("implement", "commit_push"),
          ),
        ),
    ),
  )
}

internal fun completedPhaseRecord(
  phaseId: String,
  outputArtifact: String? = null,
): Map<String, Any?> =
  linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "private_phase_record",
    "phase_id" to phaseId,
    "status" to "completed",
    "attempt_count" to 1,
    "started_at" to "2026-08-09T10:00:00Z",
    "first_started_at" to "2026-08-09T10:00:00Z",
    "finished_at" to "2026-08-09T10:01:00Z",
    "resolved_agent_id" to "agent-$phaseId",
    "execution_origin" to "agent-executed",
  ).apply {
    outputArtifact?.let { put("output_artifact", it) }
  }

internal fun completedPhaseRecords(vararg phaseIds: String): Map<String, Any?> =
  phaseIds.associateWith { completedPhaseRecord(it) }

internal fun decompositionPlanPatch(
  parentSpec: Path,
  subtaskSpec: Path,
  subtaskTwo: Path? = null,
  executionModel: String = "same_branch_commit_per_subtask",
): WorkflowArtifactPatch {
  val subtasks =
    mutableListOf(
      mapOf(
        "id" to 1,
        "name" to "foundation",
        "spec_path" to subtaskSpec.toString(),
        "depends_on" to emptyList<Int>(),
      ),
    )
  if (subtaskTwo != null) {
    subtasks +=
      mapOf(
        "id" to 2,
        "name" to "runtime",
        "spec_path" to subtaskTwo.toString(),
        "depends_on" to listOf(1),
      )
  }
  val plan =
    linkedMapOf<String, Any?>(
      "mode" to "decompose",
      "parent_spec_path" to parentSpec.toString(),
      "recommended_first_subtask_id" to 1,
      "subtasks" to subtasks,
    )
  if (executionModel == "stacked_branches") {
    plan["execution_model"] = "stacked_branches"
    plan["base_branch"] = "main"
    plan["stack_branches"] =
      listOf(
        mapOf("subtask_id" to 1, "branch" to "feat/SKILL-51-demo-1", "base_branch" to "main"),
        mapOf("subtask_id" to 2, "branch" to "feat/SKILL-51-demo-2", "base_branch" to "feat/SKILL-51-demo-1"),
      ).take(subtasks.size)
  }
  return WorkflowArtifactPatch.from(
    mapOf(
      "branch" to mapOf("branch" to "feat/SKILL-51-demo"),
      "plan" to plan,
    ),
  )!!
}

internal fun decompositionPlanningResultFromPatch(patch: WorkflowArtifactPatch): DecompositionPlanningResult =
  DecompositionPlanningResult.fromWireMap(
    requireNotNull(JsonCodec.anyToStringAnyMap(patch["plan"])) {
      "Decomposition plan artifact is required."
    },
  )

internal fun writeSpecs(
  parentSpec: Path,
  vararg subtasks: Path,
) {
  Files.createDirectories(parentSpec.parent)
  Files.writeString(parentSpec, "---\nstatus: Pending\n---\n\n# Parent\n\n## Status\n\nPending\n")
  subtasks.forEach { subtask ->
    Files.writeString(subtask, "---\nstatus: Pending\n---\n\n# Subtask")
  }
}

internal fun statusLine(path: Path): String =
  Files.readAllLines(path).first { it.startsWith("status: ") }.removePrefix("status: ")

internal fun statusSection(path: Path): String {
  val lines = Files.readAllLines(path)
  val statusHeading = lines.indexOf("## Status")
  return lines.drop(statusHeading + 1).first(String::isNotBlank)
}

internal fun decodeArtifactsForTest(artifactsJson: String): Map<String, Any?> =
  JsonCodec.parseObjectOrNull(artifactsJson)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
    .orEmpty()

internal fun FeatureTaskRuntimePhaseRecorder.appendPlanLedger(
  workflowId: String,
  action: FeatureTaskRuntimePhaseLedgerAction,
): Boolean =
  appendLedgerEntry(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = workflowId,
      action = action,
      phaseId = "plan",
      attemptCount = 1,
      resolvedAgentId = "agent-plan-1",
    ),
  )

internal fun FeatureTaskRuntimePhaseRecorder.recordPlanPhase(
  workflowId: String,
  status: String,
  finished: Boolean,
  outputArtifact: String? = null,
): Boolean =
  recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = workflowId,
      phaseId = "plan",
      status = status,
      attemptCount = 1,
      resolvedAgentId = "agent-plan-1",
      finished = finished,
      outputArtifact = outputArtifact,
    ),
  )

internal fun FeatureTaskRuntimePhaseRecorder.recordRuntimePhase(
  workflowId: String,
  phaseId: String,
  status: String,
  finished: Boolean,
  blockedReason: String? = null,
): Boolean =
  recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = workflowId,
      phaseId = phaseId,
      status = status,
      attemptCount = 1,
      resolvedAgentId = "agent-$phaseId-1",
      finished = finished,
      blockedReason = blockedReason,
    ),
  )

internal fun expectedStepStatusForRecord(record: FeatureTaskRuntimePhaseRecord): String =
  when {
    record.status == WorkflowStepStatus.BLOCKED -> "blocked"
    record.finishedAt != null -> "completed"
    else -> record.status.wireValue
  }

internal fun decodeStepsForTest(
  repository: InMemoryWorkflowStateRepository,
  workflowId: String,
): List<Pair<String, String>> {
  val stepsJson = requireNotNull(repository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).stepsJson
  val element = JsonCodec.json.parseToJsonElement(stepsJson)
  return (JsonCodec.jsonElementToValue(element) as List<*>).map { raw ->
    val item = raw as Map<*, *>
    item["step_id"].toString() to item["status"].toString()
  }
}

internal fun stepStatusFor(
  repository: InMemoryWorkflowStateRepository,
  workflowId: String,
  stepId: String,
): String = decodeStepsForTest(repository, workflowId).first { it.first == stepId }.second

internal fun assertRuntimeWorkflowRow(
  repository: InMemoryWorkflowStateRepository,
  workflowId: String,
  currentStepId: String,
  workflowStatus: String,
) {
  val row = requireNotNull(repository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
  assertEquals(currentStepId, row.currentStepId)
  assertEquals(workflowStatus, row.workflowStatus)
}

internal fun testWorkflowService(
  database: DatabaseSessionFactory,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
): WorkflowService =
  WorkflowService(
    database = database,
    gitOperations = gitOperations,
    decompositionManifestStore = FileSystemDecompositionManifestFileStore(),
    workflowSnapshotValidator = WorkflowStateSchemaValidator(),
    decompositionManifestValidator = DecompositionManifestSchemaValidator(),
    decompositionManifestWriter = DecompositionManifestWriter(),
    repositoryRoot = RepositoryRoot(Path.of("").toAbsolutePath().normalize()),
    goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
    runtimeDiagnostics = NoopRuntimeDiagnostics,
    clock = Clock.systemUTC(),
  )

internal fun loadTestDecompositionManifest(path: Path) =
  loadDecompositionManifest(path, FileSystemDecompositionManifestFileStore(), DecompositionManifestSchemaValidator())

internal class InMemoryWorkflowStateRepository : WorkflowStateRepositoryDefaults() {
  private val implementRows = linkedMapOf<String, WorkflowStateRecord>()
  private val verifyRows = linkedMapOf<String, WorkflowStateRecord>()
  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()
  var failNextRuntimeSave: Boolean = false

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) {
    when (mode) {
      RUNTIME -> {
        if (failNextRuntimeSave) {
          failNextRuntimeSave = false
          error("save failed")
        }
        taskRuntimeRows[row.workflowId] = row
      }
      PROSE -> implementRows[row.workflowId] = row
    }
  }

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    taskRuntimeRows[workflowId] ?: implementRows[workflowId]

  override fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? =
    getFeatureTaskWorkflow(workflowId)?.also { row ->
      val actualMode = row.mode ?: PROSE
      if (actualMode != mode) {
        throw InvalidWorkflowStateSchemaError("Unexpected feature-task workflow mode.")
      }
    }

  override fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> =
    when (mode) {
      RUNTIME -> taskRuntimeRows
      PROSE -> implementRows
    }.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    listFeatureTaskWorkflows(mode, Int.MAX_VALUE).firstOrNull()

  override fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) {
    val source =
      when (family) {
        WorkflowFamily.VERIFY -> verifyRows[snapshot.workflowId]
        WorkflowFamily.TASK_RUNTIME -> taskRuntimeRows[snapshot.workflowId]
      }
    saveRecord(family, snapshot.toRecord(source))
  }

  override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) {
    when (family) {
      WorkflowFamily.VERIFY -> verifyRows[record.workflowId] = record
      WorkflowFamily.TASK_RUNTIME -> saveFeatureTaskWorkflow(record, RUNTIME)
    }
  }

  override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? = record(family, workflowId)?.toSnapshot()

  override fun getAll(
    family: WorkflowFamily,
    workflowIds: Set<String>,
  ): Map<String, WorkflowStateSnapshot> =
    workflowIds.mapNotNull { id -> record(family, id)?.let { id to it.toSnapshot() } }.toMap()

  override fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot> =
    when (family) {
      WorkflowFamily.VERIFY -> verifyRows.values.toList().asReversed().take(limit)
      WorkflowFamily.TASK_RUNTIME -> listFeatureTaskWorkflows(RUNTIME, limit)
    }.map(WorkflowStateRecord::toSnapshot)

  override fun latest(family: WorkflowFamily): WorkflowStateSnapshot? = list(family, 1).firstOrNull()

  private fun record(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateRecord? =
    when (family) {
      WorkflowFamily.VERIFY -> verifyRows[workflowId]
      WorkflowFamily.TASK_RUNTIME -> taskRuntimeRows[workflowId]
    }
}

internal class FakeWorkflowGitOperations(
  private val commitSha: String = "commit-sha",
  private val commitError: String = "",
) : WorkflowGitOperationsTestBase() {
  val checkouts = mutableListOf<String>()
  val baseValidations = mutableListOf<String>()
  val commits = mutableListOf<String>()

  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult {
    checkouts += "$branch@${baseBranch.orEmpty()}"
    return WorkflowGitOperationResult.Ok(value = branch)
  }

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = checkouts.lastOrNull()?.substringBefore("@").orEmpty())

  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult {
    commits += message
    if (commitError.isNotBlank()) {
      return WorkflowGitCommitResult.Failed(error = commitError)
    }
    return WorkflowGitCommitResult.Committed(commitSha = commitSha)
  }

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = commitSha)

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult {
    baseValidations += "$branch@$expectedBaseBranch"
    return WorkflowGitOperationResult.Ok(value = expectedBaseBranch)
  }

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = WorkflowGitOperationStatus.OK)

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = WorkflowGitOperationStatus.OK)

  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "test-repository-fingerprint")

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult = repositoryFingerprint(repoRoot)
}

internal fun evaluatorReceipt(verdict: String): Map<String, Any?> =
  mapOf(
    "contract_version" to "0.3",
    "verdict" to verdict,
    "findings" to emptyList<Any>(),
  )

internal fun learningRecord(
  id: Int,
  title: String = "Learning $id",
): LearningRecord =
  LearningRecord(
    id = id,
    scope = "global",
    scopeKey = "global",
    title = title,
    ruleText = "Rule $id",
    rationale = "",
    status = "active",
    sourceReviewRunId = "rvw-1",
    sourceFindingId = "F-$id",
    createdAt = "2026-04-24 00:00:00",
    updatedAt = "2026-04-24 00:00:00",
  )

internal fun numberedFinding(
  number: Int,
  findingId: String,
): NumberedFinding =
  NumberedFinding(
    number = number,
    findingId = findingId,
    severity = "Major",
    confidence = "High",
    location = "README.md:1",
    description = "Example finding",
  )

internal fun testPhaseRecorder(database: DatabaseSessionFactory) =
  featureTaskRuntimePhaseRecorder(
    database,
    WorkflowStateSchemaValidator(),
    FeatureTaskRuntimeWireArtifactSchemaValidator(),
    FeatureTaskRuntimeWireArtifactSchemaValidator(),
    Clock.systemUTC(),
    NoopRuntimeDiagnostics,
  )

internal fun openTaskRuntimeWorkflow(database: DatabaseSessionFactory): String =
  (
    testWorkflowService(database)
      .openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-envelope")
      as WorkflowOpenResult.Ok
  ).workflowId

internal fun handoffEnvelope() =
  FeatureTaskRuntimeHandoffEnvelope(
    consumerPhaseId = "implement",
    projections =
      listOf(
        FeatureTaskRuntimeHandoffProjection(
          projectionName = "plan_receipt",
          sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput("plan"),
          projectionContractId = "feature_task_runtime.upstream_phase_receipt",
          projectionContractVersion = "0.1",
          promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
          fields =
            listOf(
              FeatureTaskRuntimeHandoffProjectionField(
                name = "phase_output_receipt",
                value =
                  FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
                    kind = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
                    value = "feature_task_runtime_phase_records/plan#1",
                  ),
              ),
            ),
        ),
      ),
    repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc"),
  )

internal fun handoffBriefing(envelope: FeatureTaskRuntimeHandoffEnvelope = handoffEnvelope()) =
  FeatureTaskRuntimePhaseLaunchBriefing(
    phaseId = "implement",
    specReference = ".feature-specs/SKILL-137/spec.md",
    featureSize = "MEDIUM",
    acceptanceCriteria = listOf("AC-003"),
    mandatesAndOverrides = emptyList(),
    handoffEnvelope = envelope,
    derivedContextKeys = emptyList(),
    briefingText = "phase: implement",
  )

internal fun corruptDurableEnvelope(
  workflowRepository: InMemoryWorkflowStateRepository,
  workflowId: String,
  corrupt: (Map<String, Any?>) -> Map<String, Any?>,
) {
  val record = requireNotNull(workflowRepository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
  val artifacts = decodeArtifactsForTest(record.artifactsJson).toMutableMap()
  val briefings =
    requireNotNull(
      JsonCodec.anyToStringAnyMap((artifacts[FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY])),
    ).toMutableMap()
  val briefing = requireNotNull(JsonCodec.anyToStringAnyMap((briefings.getValue("implement")))).toMutableMap()
  briefing["handoff_envelope"] =
    corrupt(
      requireNotNull(JsonCodec.anyToStringAnyMap(briefing.getValue("handoff_envelope"))),
    )
  briefings["implement"] = briefing
  artifacts[FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY] = briefings
  workflowRepository.saveFeatureTaskWorkflow(
    record.copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
    RUNTIME,
  )
}

internal fun telemetrySyncService(reconciliation: RecordingTelemetryReconciliationRepository): TelemetryService {
  val database =
    FakeDatabaseSessionFactory(
      telemetryOutbox = InMemoryTelemetryOutboxRepository(),
      telemetryReconciliation = reconciliation,
    )
  val settingsProvider = FakeTelemetrySettingsProvider(enabled = true)
  return TelemetryService(
    database = database,
    settingsProvider = settingsProvider,
    telemetryClient = FakeTelemetryClient(),
    clock = Clock.systemUTC(),
    levelMutationService =
      TelemetryLevelMutationService(
        database = database,
        settingsProvider = settingsProvider,
        configStore = FakeTelemetryConfigStore,
      ),
    diagnostics = NoopRuntimeDiagnostics,
    interruptSignal = JvmInterruptSignalPort,
  )
}
