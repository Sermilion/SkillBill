package skillbill.engine.goalrunner.execution.core

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.lifecycle.noopGoalLifecycleTelemetryEmitter
import skillbill.application.testDecompositionManifestValidator
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.slot.goalPlanningPhaseStrategies
import skillbill.engine.goalplanning.GoalPlanningMigrationAdmission
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.GoalRunner
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.intake.GoalIntakePreparation
import skillbill.engine.goalrunner.launch.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.launch.GoalRunnerSubtaskLaunchPrepare
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.TestNoopGoalPlanningManifestStore
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.planningMigrationForTest
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningPhaseAttemptGate
import skillbill.engine.goalrunner.planning.attempt.NO_GOAL_PLANNING_ATTEMPT_RECORDER
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedPreplanProduction
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedPreplanSettlement
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.engine.goalrunner.planning.outcome.GoalPlanningSubtaskPlanProduction
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRefreshLiveness
import skillbill.engine.goalrunner.planning.recovery.GoalRunnerSpecDriftRecovery
import skillbill.engine.goalrunner.planning.recovery.IDLE_GOAL_PLANNING_REFRESH_LIVENESS
import skillbill.engine.goalrunner.planning.remedies.GoalPlanningRejectionRecorder
import skillbill.engine.goalrunner.planning.remedies.NO_GOAL_PLANNING_REJECTION_RECORDER
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.PREPARE_ALL_GOAL_PLANNING_SWEEP
import skillbill.engine.worktreeedit.WorktreeEditJournalWriter
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.concurrency.SequentialBoundedWorkFanOutPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.planning.EMPTY_GOAL_PLANNING_CONTEXT_DISCOVERY
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.ports.workflow.specscratch.UnavailableSpecScratchStore
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock
import kotlin.random.Random
import kotlin.time.TimeSource

private const val GOAL_RUNNER_TEST_WORKFLOW_ID_SEED: Int = 20260923

internal fun testActivityStampWriter(
  database: DatabaseSessionFactory = TestGoalActivityStampDatabase,
): AgentActivityStampWriter =
  AgentActivityStampWriter(database, Clock.systemUTC(), NoopRuntimeDiagnostics, TimeSource.Monotonic)

internal fun testWorktreeEditJournalWriter(
  database: DatabaseSessionFactory = TestGoalActivityStampDatabase,
): WorktreeEditJournalWriter =
  WorktreeEditJournalWriter(
    database,
    Clock.systemUTC(),
    NoopRuntimeDiagnostics,
    NoopWorkflowGitOperations,
  )

internal data class GoalRunnerTestWiring(
  val manifestStore: GoalRunnerManifestStore,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val goalPlanningSweep: GoalPlanningSweep,
  val specScratchStore: SpecScratchStore,
  val gitOperations: WorkflowGitOperations,
  val telemetry: GoalLifecycleTelemetryEmitter,
  val clock: Clock,
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  val executionCoordinator: GoalRunnerExecutionCoordinator,
  val phaseQuery: FeatureTaskRuntimePhaseQuery?,
  val diagnostics: RuntimeDiagnostics,
)

internal data class GoalRunnerTestWiringParams(
  val manifestStore: GoalRunnerManifestStore,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService? = null,
)

internal fun testGoalRunnerWiring(params: GoalRunnerTestWiringParams): GoalRunnerTestWiring {
  return GoalRunnerTestWiring(
    manifestStore = params.manifestStore,
    subtaskLauncher = params.subtaskLauncher,
    outcomeStore = params.outcomeStore,
    pullRequestPort = params.pullRequestPort,
    goalPlanningSweep = PREPARE_ALL_GOAL_PLANNING_SWEEP,
    specScratchStore = UnavailableSpecScratchStore,
    gitOperations = NoopWorkflowGitOperations,
    telemetry = noopGoalLifecycleTelemetryEmitter,
    clock = Clock.systemUTC(),
    unaddressedFindingsLedgerService = params.unaddressedFindingsLedgerService,
    executionCoordinator = DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR,
    phaseQuery = params.phaseRecorder.phaseQuery,
    diagnostics = NoopRuntimeDiagnostics,
  )
}

internal data class GoalRunnerTestInputs(
  val manifestStore: GoalRunnerManifestStore,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val goalPlanningSweep: GoalPlanningSweep = PREPARE_ALL_GOAL_PLANNING_SWEEP,
  val specScratchStore: SpecScratchStore = UnavailableSpecScratchStore,
  val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val telemetry: GoalLifecycleTelemetryEmitter = noopGoalLifecycleTelemetryEmitter,
  val clock: Clock = Clock.systemUTC(),
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService? = null,
  val executionCoordinator: GoalRunnerExecutionCoordinator = DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
) {
  fun toWiring(): GoalRunnerTestWiring =
    GoalRunnerTestWiring(
      manifestStore = manifestStore,
      subtaskLauncher = subtaskLauncher,
      outcomeStore = outcomeStore,
      pullRequestPort = pullRequestPort,
      goalPlanningSweep = goalPlanningSweep,
      specScratchStore = specScratchStore,
      gitOperations = gitOperations,
      telemetry = telemetry,
      clock = clock,
      unaddressedFindingsLedgerService = unaddressedFindingsLedgerService,
      executionCoordinator = executionCoordinator,
      phaseQuery = phaseRecorder.phaseQuery,
      diagnostics = NoopRuntimeDiagnostics,
    )
}

internal fun goalRunnerDeps(
  manifestStore: GoalRunnerManifestStore,
  subtaskLauncher: GoalRunnerSubtaskLauncher,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  pullRequestPort: GoalPullRequestPort,
): GoalRunnerTestInputs =
  GoalRunnerTestInputs(
    manifestStore = manifestStore,
    subtaskLauncher = subtaskLauncher,
    outcomeStore = outcomeStore,
    pullRequestPort = pullRequestPort,
  )

internal fun testGoalRunner(deps: GoalRunnerTestInputs): GoalRunner = testGoalRunner(deps.toWiring())

internal fun testSpecDriftRecovery(
  manifestStore: GoalRunnerManifestStore,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
): GoalRunnerSpecDriftRecovery =
  GoalRunnerSpecDriftRecovery(
    GoalPlanningPreparationCheckpoint(
      TestGoalActivityStampDatabase,
      FeatureTaskRuntimeWireArtifactValidator(),
    ),
    manifestStore,
    UnavailableDecompositionManifestStore,
    TestRepositoryEnclosingRoot,
    testGoalRunnerStatusService(manifestStore, outcomeStore),
    FeatureTaskRuntimeWireArtifactValidator(),
    NoopRuntimeDiagnostics,
  )

internal fun testGoalRunner(wiring: GoalRunnerTestWiring): GoalRunner {
  val (executionPlans, crashReconciler) = goalRunnerExecutionPlans(wiring)
  val progressReader = GoalRunnerProgressReader(wiring.outcomeStore)
  val finalization =
    GoalRunnerFinalization(
      wiring.manifestStore,
      wiring.outcomeStore,
      wiring.pullRequestPort,
      wiring.specScratchStore,
      wiring.gitOperations,
      wiring.diagnostics,
      wiring.unaddressedFindingsLedgerService,
      progressReader,
    )
  val pauseBoundary = GoalRunnerPauseBoundary(wiring.manifestStore)
  val perRunLoopAssembler =
    testGoalRunnerLoopAssembler(wiring, executionPlans, progressReader, finalization, pauseBoundary)
  return GoalRunner(
    manifestStore = wiring.manifestStore,
    outcomeStore = wiring.outcomeStore,
    goalPlanningSweep = wiring.goalPlanningSweep,
    telemetry = wiring.telemetry,
    clock = wiring.clock,
    diagnostics = wiring.diagnostics,
    executionCoordinator = wiring.executionCoordinator,
    runPreparation =
      GoalRunnerRunPreparation(
        wiring.manifestStore,
        TestRepositoryEnclosingRoot,
        executionPlans,
        crashReconciler,
        testSpecDriftRecovery(wiring.manifestStore, wiring.outcomeStore),
        GoalPlanningMigrationAdmission(
          TestGoalActivityStampDatabase,
          planningMigrationForTest(),
          wiring.diagnostics,
        ),
      ),
    perRunLoopAssembler = perRunLoopAssembler,
    pauseBoundary = pauseBoundary,
    intakePreparation =
      GoalIntakePreparation(
        wiring.manifestStore,
        FeatureSpecPreparationWriter(
          testDecompositionManifestValidator,
          UnavailableDecompositionManifestStore,
          DecompositionManifestWriter(),
        ),
        FeatureSpecPathResolverPort { input -> FeatureSpecPathResolveResult.NoMatch(input.issueKey, input.repoRoot) },
        UnavailableDecompositionManifestStore,
        NoopWorkflowGitOperations,
      ),
  )
}

private fun testGoalRunnerLoopAssembler(
  wiring: GoalRunnerTestWiring,
  executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  progressReader: GoalRunnerProgressReader,
  finalization: GoalRunnerFinalization,
  pauseBoundary: GoalRunnerPauseBoundary,
): GoalRunnerPerRunLoopAssembler {
  val workerRequestHandler =
    GoalRunnerWorkerRequestHandler(
      wiring.manifestStore,
      wiring.outcomeStore,
    )
  val reconciler =
    GoalRunnerLaunchReconciler(
      wiring.manifestStore,
      wiring.outcomeStore,
      progressReader,
      testActivityStampWriter(),
      testWorktreeEditJournalWriter(),
      wiring.clock,
      wiring.diagnostics,
    )
  val launchPrepare =
    GoalRunnerSubtaskLaunchPrepare(
      wiring.manifestStore,
      wiring.outcomeStore,
      wiring.gitOperations,
      TestRepositoryEnclosingRoot,
      wiring.clock,
      Random(GOAL_RUNNER_TEST_WORKFLOW_ID_SEED),
      executionPlans,
    )
  val iterationOutcome =
    GoalRunnerIterationOutcome(
      manifestStore = wiring.manifestStore,
      outcomeStore = wiring.outcomeStore,
      finalization = finalization,
      unaddressedFindingsLedgerService = wiring.unaddressedFindingsLedgerService,
      progressReader = progressReader,
      clock = wiring.clock,
      phaseQuery = wiring.phaseQuery,
    )
  val selectedSubtaskLoop =
    GoalRunnerSelectedSubtaskLoop(
      manifestStore = wiring.manifestStore,
      subtaskLauncher = wiring.subtaskLauncher,
      reconciler = reconciler,
      workerRequestHandler = workerRequestHandler,
      iterationOutcome = iterationOutcome,
      pauseBoundary = pauseBoundary,
      launchPrepare = launchPrepare,
      clock = wiring.clock,
    )
  return GoalRunnerPerRunLoopAssembler(
    manifestStore = wiring.manifestStore,
    goalPlanningSweep = wiring.goalPlanningSweep,
    finalization = finalization,
    selectedSubtaskLoop = selectedSubtaskLoop,
    pauseBoundary = pauseBoundary,
    progressReader = progressReader,
  )
}

internal fun testGoalRunner(
  manifestStore: GoalRunnerManifestStore,
  subtaskLauncher: GoalRunnerSubtaskLauncher,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  pullRequestPort: GoalPullRequestPort,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
): GoalRunner =
  testGoalRunner(
    testGoalRunnerWiring(
      GoalRunnerTestWiringParams(
        manifestStore = manifestStore,
        subtaskLauncher = subtaskLauncher,
        outcomeStore = outcomeStore,
        pullRequestPort = pullRequestPort,
        phaseRecorder = phaseRecorder,
      ),
    ),
  )

private object TestGoalActivityStampDatabase : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-activity-stamp.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@TestGoalActivityStampDatabase.dbPath
      override val reviews: ReviewRepository get() = error("unused by goal activity stamp wiring")
      override val learnings: LearningRepository get() = error("unused by goal activity stamp wiring")
      override val lifecycleTelemetry: LifecycleTelemetryRepository
        get() = error("unused by goal activity stamp wiring")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("unused by goal activity stamp wiring")
      override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by goal activity stamp wiring")
      override val workflowStates: WorkflowStateRepository get() = error("unused by goal activity stamp wiring")
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

internal data class GoalPlanningSweepPortsParams(
  val runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
  val checkpoint: GoalPlanningPreparationCheckpoint,
  val clock: Clock = Clock.systemUTC(),
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  val manifestFileStore: DecompositionManifestStore,
  val contextDiscovery: GoalPlanningContextDiscovery,
  val planningAttemptRecorder: GoalPlanningAttemptRecorder = NO_GOAL_PLANNING_ATTEMPT_RECORDER,
  val manifestStore: GoalRunnerManifestStore = TestNoopGoalPlanningManifestStore,
  val planningRejectionRecorder: GoalPlanningRejectionRecorder = NO_GOAL_PLANNING_REJECTION_RECORDER,
  val timingPort: RuntimeTimingPort = NoopRuntimeTimingPort,
  val fanOutPort: BoundedWorkFanOutPort = SequentialBoundedWorkFanOutPort,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
  val burstSchedule: GoalPlanningBurstSchedule =
    GoalPlanningBurstSchedule(
      planFanOutCap = GoalPlanningBurstSchedule.DEFAULT_PLAN_FAN_OUT_CAP,
      emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
      emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
      waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
    ),
  val refreshLiveness: GoalPlanningRefreshLiveness = IDLE_GOAL_PLANNING_REFRESH_LIVENESS,
)

internal fun testGoalPlanningSweepPorts(params: GoalPlanningSweepPortsParams): DefaultGoalPlanningSweep {
  val attemptGate =
    GoalPlanningPhaseAttemptGate(
      manifestStore = params.manifestStore,
      planningAttemptRecorder = params.planningAttemptRecorder,
      planningRejectionRecorder = params.planningRejectionRecorder,
      timingPort = params.timingPort,
      burstSchedule = params.burstSchedule,
      clock = params.clock,
    )
  val sharedPreplanProduction =
    GoalPlanningSharedPreplanProduction(
      checkpoint = params.checkpoint,
      invariantsSource = params.invariantsSource,
      manifestFileStore = params.manifestFileStore,
      contextDiscovery = params.contextDiscovery,
      repositoryEnclosingRootPort = params.repositoryEnclosingRootPort,
      attemptGate = attemptGate,
      migrationAdmission =
        GoalPlanningMigrationAdmission(
          TestGoalActivityStampDatabase,
          planningMigrationForTest(),
          NoopRuntimeDiagnostics,
        ),
    )
  return DefaultGoalPlanningSweep(
    sharedPreplanProduction = sharedPreplanProduction,
    sharedPreplanSettlement =
      GoalPlanningSharedPreplanSettlement(
        checkpoint = params.checkpoint,
        sharedPreplanProduction = sharedPreplanProduction,
        contextDiscovery = params.contextDiscovery,
        manifestFileStore = params.manifestFileStore,
        repositoryEnclosingRootPort = params.repositoryEnclosingRootPort,
        refreshLiveness = params.refreshLiveness,
      ),
    planProduction =
      GoalPlanningSubtaskPlanProduction(
        attemptGate = attemptGate,
        checkpoint = params.checkpoint,
        invariantsSource = params.invariantsSource,
        manifestFileStore = params.manifestFileStore,
        repositoryEnclosingRootPort = params.repositoryEnclosingRootPort,
      ),
    attemptGate = attemptGate,
    checkpoint = params.checkpoint,
    repositoryEnclosingRootPort = params.repositoryEnclosingRootPort,
    phaseStrategies =
      goalPlanningPhaseStrategies(params.subtaskLauncher, params.fanOutPort, params.burstSchedule.planFanOutCap),
    clock = params.clock,
    diagnostics = NoopRuntimeDiagnostics,
    runLoopEntry = params.runLoopEntry,
    manifestFileStore = params.manifestFileStore,
  )
}

internal fun testGoalPlanningContextDiscovery(
  context: GoalPlanningContext =
    GoalPlanningContext(
      boundaryCatalog = emptyList(),
      boundaryCatalogTruncated = false,
      validationGuidance = "",
    ),
): GoalPlanningContextDiscovery =
  if (context ==
    GoalPlanningContext(
      boundaryCatalog = emptyList(),
      boundaryCatalogTruncated = false,
      validationGuidance = "",
    )
  ) {
    EMPTY_GOAL_PLANNING_CONTEXT_DISCOVERY
  } else {
    object : GoalPlanningContextDiscovery {
      override fun loadPlanningContext(repoRoot: Path): GoalPlanningContext = context

      override fun discoverForFindingPaths(
        repoRoot: Path,
        findingPaths: List<String>,
        loudFailOnCapExceeded: Boolean,
      ) = EMPTY_GOAL_PLANNING_CONTEXT_DISCOVERY.discoverForFindingPaths(
        repoRoot,
        findingPaths,
        loudFailOnCapExceeded,
      )
    }
  }

private fun goalRunnerExecutionPlans(
  wiring: GoalRunnerTestWiring,
): Pair<FeatureTaskRuntimeExecutionPlanResolver, FeatureTaskRuntimeCrashReconciler> {
  val states = InMemoryRuntimeWorkflowRepository()
  val database = FakeDatabaseSessionFactory(states)
  val fixture = ExecutionPlanAdmissionFixture(database = database)
  val resolver = fixture.creationResolver()
  val reconciler =
    FeatureTaskRuntimeCrashReconciler(
      database,
      NoopFeatureTaskRuntimeWorkerSupervisor,
      wiring.diagnostics,
      wiring.clock,
      fixture.compatibility,
      fixture.recoveryResolver(),
    )
  val store = wiring.manifestStore as? InMemoryGoalManifestStore ?: return resolver to reconciler
  val manifest = store.manifest
  manifest.subtasks.forEach { subtask ->
    val workflowId = subtask.workflowId ?: return@forEach
    val descriptor =
      resolver.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          Path.of("/tmp/skillbill-goal-runner"),
          SkeletonDefinition.GOAL_CHILD,
          CodeReviewExecutionMode.DEFAULT,
          GoalRunnerQualityGateSelectionResolver.resolve(manifest, subtask.id),
          ValidationDepth.FULL,
          null,
        ),
      )
    fixture.seed(states, workflowId, manifest.issueKey, fixture.validator.read(descriptor.encoded(), "goal fixture"))
  }
  return resolver to reconciler
}
