package skillbill.engine.work

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.status.IDE_STATUS_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.lease
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.engine.work.model.IdeStatusResult
import skillbill.engine.work.model.toStatusWireMap
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.NoopIdeStatusValidator
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal val ideStatusObservedAt: Instant = Instant.parse("2026-08-06T12:00:00Z")
internal val ideStatusClock: Clock = Clock.fixed(ideStatusObservedAt, ZoneOffset.UTC)
private val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()

internal fun testGoalRepositoryIdentity(repoRoot: Path): String =
  goalRepositoryIdentity(repoRoot, TestRepositoryEnclosingRoot)

internal fun goalWireMapUnderControls(
  fixtureName: String,
  controlState: GoalRunnerControlState,
  assertSnapshot: (IdeStatusResult) -> Unit,
): Map<String, Any?> {
  val fixture = gitRepoFixture(fixtureName)
  val identity = testGoalRepositoryIdentity(fixture)
  val service =
    ideStatusService(
      goalOnlyDatabase(),
      manifestStore =
        StubGoalManifestStore(
          goalManifestState(fixture, identity, childWorkflowId = "w-child")
            .copy(controlState = controlState.copy(repositoryIdentity = identity)),
        ),
    )

  val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

  assertSnapshot(result)
  return result.snapshot.toStatusWireMap()
}

internal fun nestedWireMap(
  wire: Map<String, Any?>,
  key: String,
): Map<String, Any?> {
  val value = wire[key]
  require(value is Map<*, *>) { "expected nested map at '$key'" }
  return buildMap {
    for ((nestedKey, nestedValue) in value) {
      if (nestedKey is String) put(nestedKey, nestedValue)
    }
  }
}

internal fun goalOnlyDatabase(goalState: String = "running"): TrackingDatabase =
  TrackingDatabase(
    work = listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, goalState, "2026-08-06T10:00:00Z")),
    workflows = IdeStatusWorkflowStates(),
  )

internal fun goalWithLaunchedChildDatabase(
  identity: String,
  childStarted: Instant,
  childArtifactsJson: String = "{}",
  childCurrentStep: String = "implement",
): TrackingDatabase {
  val workflows = IdeStatusWorkflowStates()
  workflows.saveFeatureTaskWorkflow(
    runtimeRecord("w-child", "2026-08-06T11:00:00Z", currentStep = childCurrentStep)
      .copy(startedAt = childStarted.toString(), artifactsJson = childArtifactsJson),
    FeatureTaskWorkflowMode.PROSE,
  )
  workflows.saveFeatureTaskExecutionIdentity(
    identityFor("w-child", identity).copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
  )
  val controls =
    object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
      override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
        GoalRunnerControlState(repositoryIdentity = identity)
    }
  return TrackingDatabase(
    work =
      listOf(
        workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z"),
        workItem("w-child", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T11:00:00Z")
          .copy(startedAt = childStarted),
      ),
    workflows = workflows,
    controls = controls,
  )
}

internal fun planningSnapshot(
  state: GoalPlanningStatusState,
  wave: List<Int> = emptyList(),
): GoalPlanningStatusSnapshot =
  GoalPlanningStatusSnapshot(
    state = state,
    sharedPreplanPrepared = true,
    plannedSubtaskCount = 1,
    totalSubtaskCount = 2,
    currentPlanningSubtaskId = wave.minOrNull() ?: 2,
    planningWaveSubtaskIds = wave,
    reason = null,
  )

internal fun goalManifestState(
  fixture: Path,
  identity: String,
  childWorkflowId: String,
): GoalRunnerManifestState =
  GoalRunnerManifestState(
    parentWorkflowId = "goal-1",
    dbPath = "/fake/ide-status.db",
    manifest =
      DecompositionManifest(
        issueKey = "SKILL-148",
        featureName = "ide-status",
        parentSpecPath = ".feature-specs/SKILL-148/spec.md",
        baseBranch = "main",
        featureBranch = "feat/SKILL-148",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "start"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "One",
              specPath = "spec_1.md",
              status = "complete",
              workflowId = "w-done",
            ),
            DecompositionSubtask(
              id = 2,
              name = "Two",
              specPath = "spec_2.md",
              status = "in_progress",
              workflowId = childWorkflowId,
              lastResumableStep = "implement",
            ),
          ),
      ),
    controlState = GoalRunnerControlState(repositoryIdentity = identity),
    repoRoot = fixture,
  )

internal fun ideStatusService(
  database: TrackingDatabase,
  manifestStore: GoalRunnerManifestStore = EmptyManifestStore,
  outcomeStore: GoalRunnerWorkflowOutcomeStore = EmptyOutcomeStore,
): IdeStatusService {
  val snapshotValidator =
    object : WorkflowSnapshotValidator {
      override fun validate(
        snapshot: WorkflowStateSnapshot,
        slug: String,
      ) = Unit
    }
  val phaseRecorder =
    featureTaskRuntimePhaseRecorder(
      database,
      snapshotValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      testHarnessClock,
      NoopRuntimeDiagnostics,
    )
  val runtimeStatusService =
    FeatureTaskRuntimeStatusService(
      recorder = phaseRecorder,
      runInvariantsStore =
        FeatureTaskRuntimeRunInvariantsStore(
          database,
          FeatureTaskRuntimeWorkflowPersistence(database, snapshotValidator),
        ),
      decomposeTerminalRecorder = FeatureTaskRuntimeDecomposeTerminalRecorder(database, testHarnessClock),
    )
  val projector =
    IdeStatusProjector(
      workflowSnapshotValidator = snapshotValidator,
      goalRunnerStatusService =
        testGoalRunnerStatusService(
          manifestStore = manifestStore,
          outcomeStore = outcomeStore,
          phaseRecorder = phaseRecorder,
          clock = ideStatusClock,
          ports =
            GoalRunnerStatusTestPorts(
              runtimeStatusService = runtimeStatusService,
            ),
        ),
      featureTaskRuntimeStatusService = runtimeStatusService,
      diagnostics = NoopRuntimeDiagnostics,
    )
  return IdeStatusService(
    database = database,
    projector = projector,
    ideStatusValidator = EmitShapeValidator,
    branchSource = CheckedOutBranchSource(::fixtureCheckedOutBranch),
    clock = ideStatusClock,
    repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
    manifestStore = manifestStore,
  )
}

internal fun fixtureCheckedOutBranch(repoRoot: Path): String? =
  runCatching { Files.readString(repoRoot.resolve(".git").resolve("HEAD")) }.getOrNull()
    ?.trim()
    ?.takeIf { it.startsWith("ref: refs/heads/") }
    ?.removePrefix("ref: refs/heads/")

internal object EmitShapeValidator : IdeStatusValidator by NoopIdeStatusValidator {
  override fun validate(
    snapshot: IdeStatusSnapshot,
    sourceLabel: String,
  ) {
    val wire = snapshot.toStatusWireMap()
    require(wire["contract_version"] == IDE_STATUS_CONTRACT_VERSION)
    require(wire["repository_identity"] is String)
    require(wire["lifecycle_state"] is String)
  }
}

internal fun gitRepoFixture(
  prefix: String,
  branch: String? = "feat/SKILL-148-fixture",
): Path {
  val root = Files.createTempDirectory(prefix)
  Files.createDirectory(root.resolve(".git"))
  if (branch != null) {
    Files.writeString(root.resolve(".git").resolve("HEAD"), "ref: refs/heads/$branch\n")
  }
  return root.toRealPath()
}

internal fun workItem(
  workflowId: String,
  kind: WorkItemKind,
  state: String,
  updatedAt: String,
): WorkItem =
  WorkItem(
    issueKey = "SKILL-148",
    workflowKind = kind,
    workflowId = workflowId,
    startedAt = Instant.parse("2026-08-06T08:00:00Z"),
    currentState = state,
    stateEnteredAt = Instant.parse(updatedAt),
    stateEnteredAtEstimated = false,
  )

internal fun identityFor(
  workflowId: String,
  repositoryIdentity: String,
): FeatureTaskExecutionIdentity =
  FeatureTaskExecutionIdentity(
    workflowId = workflowId,
    normalizedIssueKey = "SKILL-148",
    repositoryIdentity = repositoryIdentity,
    governedSpecPath = "spec.md",
    mode = FeatureTaskWorkflowMode.RUNTIME,
  )

internal data class PhaseRecordOptions(
  val effort: String? = null,
  val attemptCount: Int = 1,
  val reviewPassNumber: Int? = null,
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val blockedReason: String? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
)

internal fun phaseRecordWire(
  phaseId: String,
  status: String,
  launchedModel: String?,
  options: PhaseRecordOptions = PhaseRecordOptions(),
): Map<String, Any?> =
  FeatureTaskRuntimePhaseRecord(
    phaseId = phaseId,
    status = status,
    attemptCount = options.attemptCount,
    startedAt = "2026-08-06T09:00:00Z",
    finishedAt = if (status == "completed") "2026-08-06T09:30:00Z" else null,
    resolvedAgentId = "claude",
    launchedModel = launchedModel,
    launchedEffort = options.effort,
    reviewPassNumber = options.reviewPassNumber,
    loopId = options.loopId,
    edgeIteration = options.edgeIteration,
    blockedReason = options.blockedReason,
    failureDisposition = options.failureDisposition,
  ).asWorkflowArtifactEntry().toWorkflowArtifactMap()

internal fun blockedQualityGateChildArtifacts(
  phaseId: String,
  blockedReason: String,
  failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
): String {
  val priorPhases = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.takeWhile { it != phaseId }
  val records =
    priorPhases.map { id -> id to phaseRecordWire(id, "completed", null) } +
      (
        phaseId to
          phaseRecordWire(
            phaseId,
            "blocked",
            null,
            options =
              PhaseRecordOptions(
                blockedReason = blockedReason,
                failureDisposition = failureDisposition,
              ),
          )
      )
  return phaseRecordsArtifactsJson(*records.toTypedArray())
}

internal fun phaseRecordsArtifactsJson(vararg records: Pair<String, Map<String, Any?>>): String =
  JsonCodec.mapToJsonString(
    mapOf(FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to records.toMap()),
  )

internal fun runtimeRecord(
  workflowId: String,
  updatedAt: String,
  currentStep: String = "implement",
): WorkflowStateRecord =
  WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = "session-$workflowId",
    workflowName = "bill-feature-task",
    contractVersion = "0.1",
    workflowStatus = if (currentStep == "pr") "completed" else "running",
    currentStepId = currentStep,
    stepsJson = pipelineStepsJson(FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds, currentStep),
    artifactsJson = "{}",
    startedAt = "2026-08-06T08:00:00Z",
    updatedAt = updatedAt,
    finishedAt = if (currentStep == "pr") updatedAt else null,
    issueKey = "SKILL-148",
    mode = FeatureTaskWorkflowMode.RUNTIME,
  )

internal fun verifyRecord(
  workflowId: String,
  updatedAt: String,
  currentStep: String = "code_review",
): WorkflowStateRecord =
  WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = "session-$workflowId",
    workflowName = "bill-feature-verify",
    contractVersion = "0.1",
    workflowStatus = WorkflowStatus.RUNNING.wireValue,
    currentStepId = currentStep,
    stepsJson = pipelineStepsJson(FeatureVerifyWorkflowDefinition.definition.stepIds, currentStep),
    artifactsJson = "{}",
    startedAt = "2026-08-06T08:00:00Z",
    updatedAt = updatedAt,
    finishedAt = null,
    issueKey = "SKILL-148",
  )

internal fun pipelineStepsJson(
  stepIds: List<String>,
  currentStep: String,
): String {
  val currentIndex =
    stepIds.indexOf(currentStep).takeIf { it >= 0 }
      ?: error("Unknown step id '$currentStep' for fixture pipeline.")
  return stepIds.mapIndexed { index, stepId ->
    val status =
      when {
        index < currentIndex -> "completed"
        index == currentIndex && currentStep == "pr" -> "completed"
        index == currentIndex -> "running"
        else -> "pending"
      }
    """{"step_id":"$stepId","status":"$status","attempt_count":1}"""
  }.joinToString(prefix = "[", postfix = "]")
}

internal class TrackingDatabase(
  internal val work: List<WorkItem>,
  internal val workflows: WorkflowStateRepository,
  internal val exists: Boolean = true,
  internal val controls: GoalRunnerControlRepository = EmptyGoalRunnerControlRepository,
) : DatabaseSessionFactory {
  var readCalls: Int = 0
    internal set
  var writeCalls: Int = 0
    internal set

  override fun resolveDbPath(): Path = Path.of("/fake/ide-status.db")

  override fun databaseExists(): Boolean = exists

  override fun <T> read(block: (UnitOfWork) -> T): T {
    readCalls += 1
    return block(unitOfWork())
  }

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T {
    writeCalls += 1
    return block(unitOfWork())
  }

  override fun <T> transaction(block: (UnitOfWork) -> T): T {
    writeCalls += 1
    return block(unitOfWork())
  }

  internal fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = Path.of("/fake/ide-status.db")
      override val workflowStates = workflows
      override val workList: WorkListRepository =
        object : WorkListRepository {
          override fun list(limit: Int?): List<WorkItem> = limit?.let(work::take) ?: work
        }
      override val goalRunnerControls = controls
      override val learnings: LearningRepository
        get() = error("Not exercised by IdeStatusServiceTest.")
      override val reviews: ReviewRepository
        get() = error("Not exercised by IdeStatusServiceTest.")
      override val lifecycleTelemetry: LifecycleTelemetryRepository
        get() = error("Not exercised by IdeStatusServiceTest.")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("Not exercised by IdeStatusServiceTest.")
      override val telemetryOutbox: TelemetryOutboxRepository
        get() = error("Not exercised by IdeStatusServiceTest.")
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
    }
}

internal class OrphanedIdentityWorkflowStates(
  internal val message: String,
) : WorkflowStateRepository by IdeStatusWorkflowStates() {
  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    throw InvalidWorkflowStateSchemaError(message)
}

internal class IdeStatusWorkflowStates : WorkflowStateRepositoryDefaults() {
  internal val implement = mutableMapOf<String, WorkflowStateRecord>()
  internal val verify = mutableMapOf<String, WorkflowStateRecord>()
  internal val identities = mutableMapOf<String, FeatureTaskExecutionIdentity>()

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) {
    identities[identity.workflowId] = identity
  }

  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    identities[workflowId]

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> =
    identities.values
      .filter {
        it.routeScope == FeatureTaskRouteScope.GOAL_CHILD &&
          it.normalizedIssueKey == normalizedIssueKey &&
          it.repositoryIdentity == repositoryIdentity
      }
      .mapNotNull { identity ->
        implement[identity.workflowId]?.let { FeatureTaskWorkflowCandidate(identity = identity, workflow = it) }
      }

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int =
    identities.values.count {
      it.normalizedIssueKey == normalizedIssueKey && it.routeScope == FeatureTaskRouteScope.GOAL_CHILD
    }

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) {
    implement[row.workflowId] = row
  }

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? = implement[workflowId]

  override fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? =
    getFeatureTaskWorkflow(workflowId)?.also { row ->
      val actualMode = row.mode ?: FeatureTaskWorkflowMode.PROSE
      require(actualMode == mode) { "Unexpected feature-task workflow mode." }
    }

  override fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> =
    implement.values
      .filter { row -> (row.mode ?: FeatureTaskWorkflowMode.PROSE) == mode }
      .take(limit)

  override fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    listFeatureTaskWorkflows(mode, Int.MAX_VALUE).lastOrNull()

  override fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) = saveRecord(family, snapshot.toRecord(rows(family)[snapshot.workflowId]))

  override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) {
    rows(family)[record.workflowId] = record
  }

  override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? = rows(family)[workflowId]?.toSnapshot()

  override fun getAll(
    family: WorkflowFamily,
    workflowIds: Set<String>,
  ): Map<String, WorkflowStateSnapshot> =
    workflowIds.mapNotNull { id -> rows(family)[id]?.let { id to it.toSnapshot() } }.toMap()

  override fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot> = rows(family).values.take(limit).map(WorkflowStateRecord::toSnapshot)

  override fun latest(family: WorkflowFamily): WorkflowStateSnapshot? = rows(family).values.lastOrNull()?.toSnapshot()

  private fun rows(family: WorkflowFamily): MutableMap<String, WorkflowStateRecord> =
    when (family) {
      WorkflowFamily.VERIFY -> verify
      WorkflowFamily.TASK_RUNTIME -> implement
    }
}

internal class StubGoalManifestStore(
  internal val state: GoalRunnerManifestState,
  internal val planning: GoalPlanningStatusSnapshot? = null,
  internal val lease: GoalRunnerExecutionLease? = null,
) : GoalRunnerManifestStoreDefaults() {
  override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? = lease

  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = state.takeIf { it.manifest.issueKey.equals(issueKey, ignoreCase = true) }

  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot? = planning

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = false

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = false

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = false
}

internal object EmptyManifestStore : GoalRunnerManifestStoreDefaults() {
  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = false

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = false

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = false
}

internal object EmptyOutcomeStore : GoalRunnerWorkflowOutcomeStore {
  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? = null

  override fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome? = null

  override fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: GoalRunnerWirePayload,
  ): GoalRunnerStoredOutcome? = null

  override fun authoritativeOutcomes(issueKey: String): Map<Int, GoalRunnerStoredOutcome> = emptyMap()

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome> = emptyMap()

  override fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
  ): String? = null

  override fun progress(workflowId: String): GoalRunnerWorkflowProgress? = null

  override fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean = false

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean = false

  override fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean = false

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): Boolean = false

  override fun ledgerSequenceWatermarks(issueKey: String): GoalRunnerLedgerSequenceWatermarks =
    GoalRunnerLedgerSequenceWatermarks()

  override fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean = false

  override fun goalSubtaskReviewState(workflowId: String): GoalSubtaskReviewState? = null

  override fun unemittedGoalReviewPasses(workflowId: String): List<GoalSubtaskReviewPassResult> = emptyList()

  override fun acknowledgeGoalReviewPass(
    workflowId: String,
    passNumber: Int,
  ): Boolean = false

  override fun progressEvents(workflowId: String) = emptyList<GoalProgressEvent>()

  override fun childWorkflowLoopIterations(workflowId: String): Map<String, Int> = emptyMap()
}
