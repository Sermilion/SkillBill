package skillbill.engine.goalrunner.execution.core

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.realPlanningProjectionValidator
import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.lifecycle.noopGoalLifecycleTelemetryEmitter
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.slot.goalPlanningPhaseStrategies
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.GoalRunner
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.launch.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.launch.GoalRunnerSubtaskLaunchPrepare
import skillbill.engine.goalrunner.manifest.TestNoopGoalPlanningManifestStore
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.attempt.NO_GOAL_PLANNING_ATTEMPT_RECORDER
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRefreshLiveness
import skillbill.engine.goalrunner.planning.recovery.IDLE_GOAL_PLANNING_REFRESH_LIVENESS
import skillbill.engine.goalrunner.planning.remedies.GoalPlanningRejectionRecorder
import skillbill.engine.goalrunner.planning.remedies.NO_GOAL_PLANNING_REJECTION_RECORDER
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepCheckpointBoundaries
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepLaunchBoundaries
import skillbill.engine.goalrunner.planning.sweep.PREPARE_ALL_GOAL_PLANNING_SWEEP
import skillbill.engine.worktreeedit.WorktreeEditJournalWriter
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.concurrency.SequentialBoundedWorkFanOutPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.planning.EMPTY_GOAL_PLANNING_CONTEXT_DISCOVERY
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.ports.workflow.specscratch.UnavailableSpecScratchStore
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
  val runBoundaries: GoalRunnerRunBoundaries,
  val launchBoundaries: GoalRunnerSubtaskLaunchBoundaries,
  val finalizationBoundaries: GoalRunnerFinalizationBoundaries,
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
  val clock = Clock.systemUTC()
  val diagnostics = NoopRuntimeDiagnostics
  val runBoundaries =
    GoalRunnerRunBoundaries(
      manifestStore = params.manifestStore,
      outcomeStore = params.outcomeStore,
      goalPlanningSweep = PREPARE_ALL_GOAL_PLANNING_SWEEP,
      telemetry = noopGoalLifecycleTelemetryEmitter,
      clock = clock,
      diagnostics = diagnostics,
      executionCoordinator = DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR,
      phaseQuery = params.phaseRecorder.phaseQuery,
      unaddressedFindingsLedgerService = params.unaddressedFindingsLedgerService,
    )
  val launchBoundaries =
    GoalRunnerSubtaskLaunchBoundaries(
      manifestStore = params.manifestStore,
      outcomeStore = params.outcomeStore,
      subtaskLauncher = params.subtaskLauncher,
      gitOperations = NoopWorkflowGitOperations,
    )
  val finalizationBoundaries =
    GoalRunnerFinalizationBoundaries(
      manifestStore = params.manifestStore,
      outcomeStore = params.outcomeStore,
      pullRequestPort = params.pullRequestPort,
      specScratchStore = UnavailableSpecScratchStore,
      gitOperations = NoopWorkflowGitOperations,
      diagnostics = diagnostics,
      unaddressedFindingsLedgerService = params.unaddressedFindingsLedgerService,
    )
  return GoalRunnerTestWiring(runBoundaries, launchBoundaries, finalizationBoundaries)
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
      runBoundaries =
        GoalRunnerRunBoundaries(
          manifestStore = manifestStore,
          outcomeStore = outcomeStore,
          goalPlanningSweep = goalPlanningSweep,
          telemetry = telemetry,
          clock = clock,
          diagnostics = NoopRuntimeDiagnostics,
          executionCoordinator = executionCoordinator,
          phaseQuery = phaseRecorder.phaseQuery,
          unaddressedFindingsLedgerService = unaddressedFindingsLedgerService,
        ),
      launchBoundaries =
        GoalRunnerSubtaskLaunchBoundaries(
          manifestStore = manifestStore,
          outcomeStore = outcomeStore,
          subtaskLauncher = subtaskLauncher,
          gitOperations = gitOperations,
        ),
      finalizationBoundaries =
        GoalRunnerFinalizationBoundaries(
          manifestStore = manifestStore,
          outcomeStore = outcomeStore,
          pullRequestPort = pullRequestPort,
          specScratchStore = specScratchStore,
          gitOperations = gitOperations,
          diagnostics = NoopRuntimeDiagnostics,
          unaddressedFindingsLedgerService = unaddressedFindingsLedgerService,
        ),
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
  val progressReader = GoalRunnerProgressReader(wiring.runBoundaries.outcomeStore)
  val finalization = GoalRunnerFinalization(wiring.finalizationBoundaries, progressReader)
  val workerRequestHandler =
    GoalRunnerWorkerRequestHandler(
      wiring.runBoundaries.manifestStore,
      wiring.runBoundaries.outcomeStore,
    )
  val reconciler =
    GoalRunnerLaunchReconciler(
      wiring.runBoundaries.manifestStore,
      wiring.runBoundaries.outcomeStore,
      progressReader,
      testActivityStampWriter(),
      testWorktreeEditJournalWriter(),
      wiring.runBoundaries.clock,
      wiring.runBoundaries.diagnostics,
    )
  val pauseBoundary = GoalRunnerPauseBoundary(wiring.runBoundaries.manifestStore)
  val launchPrepare =
    GoalRunnerSubtaskLaunchPrepare(
      wiring.launchBoundaries,
      TestRepositoryEnclosingRoot,
      wiring.runBoundaries.clock,
      Random(GOAL_RUNNER_TEST_WORKFLOW_ID_SEED),
    )
  val perRunLoopAssembler =
    GoalRunnerPerRunLoopAssembler(
      runBoundaries = wiring.runBoundaries,
      launchBoundaries = wiring.launchBoundaries,
      workerRequestHandler = workerRequestHandler,
      reconciler = reconciler,
      progressReader = progressReader,
      pauseBoundary = pauseBoundary,
      launchPrepare = launchPrepare,
      finalization = finalization,
    )
  return GoalRunner(
    runBoundaries = wiring.runBoundaries,
    runPreparation = GoalRunnerRunPreparation(wiring.runBoundaries.manifestStore, TestRepositoryEnclosingRoot),
    perRunLoopAssembler = perRunLoopAssembler,
    pauseBoundary = pauseBoundary,
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

internal fun testDefaultGoalPlanningSweep(
  checkpointBoundaries: GoalPlanningSweepCheckpointBoundaries,
  launchBoundaries: GoalPlanningSweepLaunchBoundaries,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
): DefaultGoalPlanningSweep =
  DefaultGoalPlanningSweep(checkpointBoundaries, launchBoundaries, repositoryEnclosingRootPort)

internal data class GoalPlanningSweepPortsParams(
  val checkpoint: GoalPlanningPreparationCheckpoint,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  val manifestFileStore: DecompositionManifestStore,
  val contextDiscovery: GoalPlanningContextDiscovery,
  val planningProjectionValidator: FeatureTaskRuntimeWireArtifactValidator = realPlanningProjectionValidator,
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

internal fun testGoalPlanningSweepPorts(params: GoalPlanningSweepPortsParams): DefaultGoalPlanningSweep =
  testDefaultGoalPlanningSweep(
    GoalPlanningSweepCheckpointBoundaries(
      checkpoint = params.checkpoint,
      outputValidator = params.outputValidator,
      invariantsSource = params.invariantsSource,
      manifestFileStore = params.manifestFileStore,
      contextDiscovery = params.contextDiscovery,
      planningProjectionValidator = params.planningProjectionValidator,
    ),
    GoalPlanningSweepLaunchBoundaries(
      manifestStore = params.manifestStore,
      planningAttemptRecorder = params.planningAttemptRecorder,
      planningRejectionRecorder = params.planningRejectionRecorder,
      timingPort = params.timingPort,
      burstSchedule = params.burstSchedule,
      refreshLiveness = params.refreshLiveness,
      phaseStrategies =
        goalPlanningPhaseStrategies(params.subtaskLauncher, params.fanOutPort, params.burstSchedule.planFanOutCap),
      clock = Clock.systemUTC(),
      diagnostics = NoopRuntimeDiagnostics,
      runLoopEntry = FeatureTaskRuntimeRunLoopEntry(),
    ),
    repositoryEnclosingRootPort = params.repositoryEnclosingRootPort,
  )

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
