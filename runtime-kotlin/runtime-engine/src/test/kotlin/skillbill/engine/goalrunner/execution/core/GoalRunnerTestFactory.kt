package skillbill.engine.goalrunner.execution.core

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.lifecycle.noopGoalLifecycleTelemetryEmitter
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.service.WorkflowService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.phaserun.testStandalonePhasePublisher
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.engine.goalrunner.GoalRunner
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.intake.GoalIntakePreparation
import skillbill.engine.goalrunner.launch.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.launch.GoalRunnerSubtaskLaunchPrepare
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.monitoring.GoalRunnerCiMonitor
import skillbill.engine.goalrunner.persist.GoalRunnerNoChangeChildCloser
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.source.GoalPlanningSource
import skillbill.engine.goalrunner.planning.source.PREPARE_ALL_GOAL_PLANNING_SOURCE
import skillbill.engine.goalrunner.qa.GoalRunnerQaGate
import skillbill.engine.worktreeedit.WorktreeEditJournalWriter
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.model.RepositoryRoot
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.passThroughResolvedLaunchModel
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.ports.workflow.specscratch.UnavailableSpecScratchStore
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.NoopGoalPlanningPreparationEnvelopeValidator
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
  val goalPlanningSource: GoalPlanningSource,
  val specScratchStore: SpecScratchStore,
  val gitOperations: WorkflowGitOperations,
  val telemetry: GoalLifecycleTelemetryEmitter,
  val clock: Clock,
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  val executionCoordinator: GoalRunnerExecutionCoordinator,
  val phaseQuery: FeatureTaskRuntimePhaseQuery?,
  val diagnostics: RuntimeDiagnostics,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  val ciMonitor: GoalRunnerCiMonitor =
    GoalRunnerCiMonitor { _, _ ->
      PhaseRunResult.Completed("test-monitor", listOf("monitor"), null, "CI passed")
    },
  val qaGate: GoalRunnerQaGate = NoopGoalRunnerQaGate,
  val pullRequestIdentityLookup: PullRequestIdentityLookup =
    PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Absent },
  val childWorkflowDatabase: DatabaseSessionFactory = TestGoalActivityStampDatabase,
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
    goalPlanningSource = PREPARE_ALL_GOAL_PLANNING_SOURCE,
    specScratchStore = UnavailableSpecScratchStore,
    gitOperations = NoopWorkflowGitOperations,
    telemetry = noopGoalLifecycleTelemetryEmitter,
    clock = Clock.systemUTC(),
    unaddressedFindingsLedgerService = params.unaddressedFindingsLedgerService,
    executionCoordinator = DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR,
    phaseQuery = params.phaseRecorder.phaseQuery,
    diagnostics = NoopRuntimeDiagnostics,
    phaseRecorder = params.phaseRecorder,
  )
}

internal data class GoalRunnerTestInputs(
  val manifestStore: GoalRunnerManifestStore,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val goalPlanningSource: GoalPlanningSource = PREPARE_ALL_GOAL_PLANNING_SOURCE,
  val specScratchStore: SpecScratchStore = UnavailableSpecScratchStore,
  val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val telemetry: GoalLifecycleTelemetryEmitter = noopGoalLifecycleTelemetryEmitter,
  val clock: Clock = Clock.systemUTC(),
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService? = null,
  val executionCoordinator: GoalRunnerExecutionCoordinator = DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
  val ciMonitor: GoalRunnerCiMonitor =
    GoalRunnerCiMonitor { _, _ ->
      PhaseRunResult.Completed("test-monitor", listOf("monitor"), null, "CI passed")
    },
  val qaGate: GoalRunnerQaGate = NoopGoalRunnerQaGate,
  val pullRequestIdentityLookup: PullRequestIdentityLookup =
    PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Absent },
  val childWorkflowDatabase: DatabaseSessionFactory = TestGoalActivityStampDatabase,
) {
  fun toWiring(): GoalRunnerTestWiring =
    GoalRunnerTestWiring(
      manifestStore = manifestStore,
      subtaskLauncher = subtaskLauncher,
      outcomeStore = outcomeStore,
      pullRequestPort = pullRequestPort,
      goalPlanningSource = goalPlanningSource,
      specScratchStore = specScratchStore,
      gitOperations = gitOperations,
      telemetry = telemetry,
      clock = clock,
      unaddressedFindingsLedgerService = unaddressedFindingsLedgerService,
      executionCoordinator = executionCoordinator,
      phaseQuery = phaseRecorder.phaseQuery,
      diagnostics = NoopRuntimeDiagnostics,
      phaseRecorder = phaseRecorder,
      childWorkflowDatabase = childWorkflowDatabase,
      ciMonitor = ciMonitor,
      qaGate = qaGate,
      pullRequestIdentityLookup = pullRequestIdentityLookup,
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

internal fun testGoalRunner(wiring: GoalRunnerTestWiring): GoalRunner {
  val (executionPlans, crashReconciler) = goalRunnerExecutionPlans(wiring)
  val progressReader = GoalRunnerProgressReader(wiring.outcomeStore)
  val finalization =
    GoalRunnerFinalization(
      wiring.outcomeStore,
      wiring.pullRequestPort,
      wiring.specScratchStore,
      wiring.gitOperations,
      wiring.diagnostics,
      wiring.unaddressedFindingsLedgerService,
      GoalRunnerFinalReconciliation(wiring.manifestStore, wiring.outcomeStore, progressReader),
      wiring.phaseRecorder,
      wiring.ciMonitor,
      wiring.qaGate,
      wiring.pullRequestIdentityLookup,
    )
  val pauseBoundary = GoalRunnerPauseBoundary(wiring.manifestStore)
  val perRunLoopAssembler =
    testGoalRunnerLoopAssembler(wiring, executionPlans, progressReader, finalization, pauseBoundary)
  return GoalRunner(
    manifestStore = wiring.manifestStore,
    outcomeStore = wiring.outcomeStore,
    goalPlanningSource = wiring.goalPlanningSource,
    telemetry = wiring.telemetry,
    clock = wiring.clock,
    diagnostics = wiring.diagnostics,
    executionCoordinator = wiring.executionCoordinator,
    runPreparation =
      GoalRunnerRunPreparation(
        wiring.manifestStore,
        wiring.outcomeStore,
        TestRepositoryEnclosingRoot,
        executionPlans,
        crashReconciler,
        testStandalonePhasePublisher(TestGoalActivityStampDatabase, wiring.clock, wiring.diagnostics),
        wiring.gitOperations,
        testGoalRunnerRepairCoordinator(
          wiring.manifestStore,
          wiring.outcomeStore,
          wiring.phaseRecorder,
          wiring.clock,
          GoalRunnerStatusTestPorts(gitOperations = wiring.gitOperations, diagnostics = wiring.diagnostics),
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
      testPhaseStrategies(wiring.subtaskLauncher, wiring.gitOperations),
      testAgentRunLauncher(),
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
      noChangePauses = wiring.phaseRecorder,
      noChangeChildCloser = testNoChangeChildCloser(wiring.childWorkflowDatabase, wiring.clock),
      clock = wiring.clock,
    )
  return GoalRunnerPerRunLoopAssembler(
    manifestStore = wiring.manifestStore,
    goalPlanningSource = wiring.goalPlanningSource,
    finalization = finalization,
    selectedSubtaskLoop = selectedSubtaskLoop,
    pauseBoundary = pauseBoundary,
    progressReader = progressReader,
  )
}

private fun testNoChangeChildCloser(
  database: DatabaseSessionFactory,
  clock: Clock,
): GoalRunnerNoChangeChildCloser =
  GoalRunnerNoChangeChildCloser(
    database,
    clock,
    WorkflowService(
      database = database,
      gitOperations = NoopWorkflowGitOperations,
      decompositionManifestStore = UnavailableDecompositionManifestStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
      decompositionManifestWriter = DecompositionManifestWriter(),
      repositoryRoot = RepositoryRoot(Path.of("/tmp/skillbill-goal-runner")),
      goalObservabilityEventValidator = NoopGoalPlanningPreparationEnvelopeValidator,
      runtimeDiagnostics = NoopRuntimeDiagnostics,
      clock = clock,
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
    ),
  )

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
  override fun <T> owningTransaction(block: (UnitOfWork) -> T) =
    OwningTransactionResult.Refused(OwningTransactionResult.Refusal.ACTIVE_SESSION)

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
          wiring.outcomeStore.recordedQualityGateSelection(workflowId)
            ?: GoalRunnerQualityGateSelectionResolver.resolve(),
          ValidationDepth.FULL,
          null,
        ),
      )
    fixture.seed(states, workflowId, manifest.issueKey, fixture.validator.read(descriptor.encoded(), "goal fixture"))
  }
  return resolver to reconciler
}

private fun testAgentRunLauncher(): AgentRunLauncher =
  object : AgentRunLauncher {
    override fun resolveLaunchModel(request: AgentRunLaunchModelRequest) = passThroughResolvedLaunchModel(request)

    override fun launch(request: AgentRunLaunchRequest) =
      error("Goal runner tests must not launch through the assignment resolver.")
  }
