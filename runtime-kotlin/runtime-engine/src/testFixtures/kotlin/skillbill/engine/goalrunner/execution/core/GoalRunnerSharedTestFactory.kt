package skillbill.engine.goalrunner.execution.core

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.application.testRepositoryRoot
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.config.model.RepoLocalConfig
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.persist.GoalRunnerAttemptLedgerStore
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.NoopGoalRunnerAttemptLedgerStore
import skillbill.engine.goalrunner.persist.OutcomeStoreTestArtifactPorts
import skillbill.engine.goalrunner.persist.engineWorkflowGoalRunnerChildRepairStore
import skillbill.engine.goalrunner.persist.engineWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.persist.engineWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPortAdapter
import skillbill.engine.goalrunner.planning.recovery.NO_GOAL_PLANNING_STATUS_REASON_COHERENCE
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairOperations
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairStore
import skillbill.engine.goalrunner.repair.GoalRunnerRepairCoordinator
import skillbill.engine.goalrunner.repair.NoopGoalRunnerChildRepairStore
import skillbill.engine.goalrunner.reset.GoalRunnerPurgeCoordinator
import skillbill.engine.goalrunner.reset.GoalRunnerResetReplanCoordinator
import skillbill.engine.goalrunner.status.GoalRunnerStatusControlVerbs
import skillbill.engine.goalrunner.status.GoalRunnerStatusProjectionAssembler
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.scaffold.model.PlatformManifest
import java.time.Clock

fun goalRunnerDefaultPhaseRecorder(): FeatureTaskRuntimePhaseRecorder =
  testPhaseRecorder(
    FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
    testWorkflowSnapshotValidator,
  )

data class GoalRunnerStatusTestPorts(
  val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  val childRepairStore: GoalRunnerChildRepairStore = NoopGoalRunnerChildRepairStore,
  val attemptLedgerStore: GoalRunnerAttemptLedgerStore = NoopGoalRunnerAttemptLedgerStore,
  val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  val runtimeStatusService: FeatureTaskRuntimeStatusService? = null,
  val validationGatePlatformManifests: List<PlatformManifest> = emptyList(),
  val repoLocalConfig: RepoLocalConfigPort =
    object : RepoLocalConfigPort {
      override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
        ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
    },
)

fun testGoalRunnerStatusService(
  manifestStore: GoalRunnerManifestStore,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
  clock: Clock = testHarnessClock,
  ports: GoalRunnerStatusTestPorts = GoalRunnerStatusTestPorts(),
  database: DatabaseSessionFactory = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
  decompositionManifestStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
): GoalRunnerStatusService {
  val projectionAssembler =
    GoalRunnerStatusProjectionAssembler(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      phaseQuery = phaseRecorder.phaseQuery,
      attemptLedgerStore = ports.attemptLedgerStore,
      database = database,
      gitOperations = ports.gitOperations,
      workerSupervisor = ports.workerSupervisor,
      planningStatusReasonCoherence = NO_GOAL_PLANNING_STATUS_REASON_COHERENCE,
      diagnostics = ports.diagnostics,
      runtimeStatusService = ports.runtimeStatusService,
      repositoryRoot = testRepositoryRoot,
    )
  return GoalRunnerStatusService(
    manifestStore = manifestStore,
    controlVerbs =
      GoalRunnerStatusControlVerbs(
        manifestStore,
        clock,
        ports.workerSupervisor,
        TestRepositoryEnclosingRoot,
      ),
    repairCoordinator =
      GoalRunnerRepairCoordinator(
        manifestStore, phaseRecorder.phaseQuery, ports.workerSupervisor,
        ports.childRepairStore, outcomeStore, testRepositoryRoot, TestRepositoryEnclosingRoot, clock, ports.diagnostics,
      ),
    acceptanceCoordinator = GoalRunnerAcceptanceCoordinator(manifestStore, outcomeStore, ports.gitOperations, clock),
    repositoryRoot = testRepositoryRoot,
    projectionAssembler = projectionAssembler,
    resetReplanCoordinator =
      GoalRunnerResetReplanCoordinator(
        manifestStore = manifestStore,
        outcomeStore = outcomeStore,
        gitOperations = ports.gitOperations,
        diagnostics = ports.diagnostics,
        projectionAssembler = projectionAssembler,
        repositoryRoot = testRepositoryRoot,
        repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      ),
    purgeCoordinator =
      GoalRunnerPurgeCoordinator(
        manifestStore = manifestStore,
        gitOperations = ports.gitOperations,
        projectionAssembler = projectionAssembler,
        manifestFileStore = decompositionManifestStore,
        manifestValidator = testDecompositionManifestValidator,
        database = database,
        repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      ),
  )
}

private val testGoalChildPlanningHydratorPort = GoalChildPlanningHydratorPortAdapter(testHarnessClock)

fun testGoalRunnerChildRepairExecutor(
  database: DatabaseSessionFactory = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  clock: Clock = testHarnessClock,
): GoalRunnerChildRepairOperations =
  GoalRunnerChildRepairOperations(
    database,
    testWorkflowSnapshotValidator,
    gitOperations,
    testDecompositionManifestValidator,
    clock,
  )

fun testWorkflowGoalRunnerManifestStore(
  database: DatabaseSessionFactory,
  decompositionManifestStore: DecompositionManifestStore,
  clock: Clock,
  decompositionManifestValidator: DecompositionManifestValidator = testDecompositionManifestValidator,
): GoalRunnerManifestStore =
  engineWorkflowGoalRunnerManifestStore(
    database = database,
    workflowSnapshotValidator = testWorkflowSnapshotValidator,
    decompositionManifestValidator = decompositionManifestValidator,
    decompositionManifestStore = decompositionManifestStore,
    clock = clock,
    decompositionManifestWriter = DecompositionManifestWriter(),
    repositoryRoot = testRepositoryRoot,
    planningHydrator = testGoalChildPlanningHydratorPort,
  )

fun testWorkflowGoalRunnerOutcomeStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator = testWorkflowSnapshotValidator,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  artifactPorts: OutcomeStoreTestArtifactPorts = OutcomeStoreTestArtifactPorts(),
  clock: Clock = testHarnessClock,
) = engineWorkflowGoalRunnerOutcomeStore(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  gitOperations = gitOperations,
  workerSupervisor = workerSupervisor,
  clock = clock,
  artifactPorts = artifactPorts,
)

fun testWorkflowGoalRunnerChildRepairStore(
  database: DatabaseSessionFactory,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  artifactPorts: OutcomeStoreTestArtifactPorts = OutcomeStoreTestArtifactPorts(),
  clock: Clock = testHarnessClock,
) = engineWorkflowGoalRunnerChildRepairStore(
  database = database,
  childRepairExecutor = testGoalRunnerChildRepairExecutor(database, gitOperations, clock),
  decompositionManifestValidator = testDecompositionManifestValidator,
  decompositionManifestWriter = testDecompositionManifestWriter,
  decompositionManifestStore = artifactPorts.decompositionManifestStore,
)

fun testPhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  handoffFoundationValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder =
  featureTaskRuntimePhaseRecorder(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    handoffEnvelopeValidator = handoffEnvelopeValidator,
    handoffFoundationValidator = handoffFoundationValidator,
    clock = testHarnessClock,
    diagnostics = diagnostics,
  )
