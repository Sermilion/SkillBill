package skillbill.engine.goalrunner.persist

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.config.model.RepoLocalConfig
import skillbill.engine.diagnostics.block.testBlockIncidentCapture
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.goalrunner.history.GoalChildHistoryEvidenceStore
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestLoader
import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestProjectionPersistence
import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.persist.quality.WorkflowGoalRunnerQualityExecutionStore
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPort
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairOperations
import skillbill.engine.goalrunner.repair.WorkflowGoalRunnerChildRepairStore
import skillbill.engine.goalrunner.reset.WorkflowGoalRunnerChildWorkflowPersistence
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.model.RepositoryRoot
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.time.Clock
import kotlin.io.path.createTempDirectory
import kotlin.random.Random

data class OutcomeStoreTestArtifactPorts(
  val goalObservabilityEventValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val goalProgressEventValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val decompositionManifestStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
) {
  fun wireArtifactValidator(): FeatureTaskRuntimeWireArtifactValidator =
    object : FeatureTaskRuntimeWireArtifactValidator {
      override fun handoffEnvelopeRejection(
        payload: FeatureTaskRuntimeWorkflowArtifactMap,
        sourceLabel: String,
      ): InvalidFeatureTaskRuntimeHandoffProjectionContext? = null

      override fun validate(
        kind: FeatureTaskRuntimeWireArtifactKind,
        payload: FeatureTaskRuntimeWorkflowArtifactMap,
        sourceLabel: String,
      ) {
        val validator =
          when (kind) {
            FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT -> goalObservabilityEventValidator
            FeatureTaskRuntimeWireArtifactKind.GOAL_PROGRESS_EVENT -> goalProgressEventValidator
            else -> AcceptingFeatureTaskRuntimeWireArtifactValidator
          }
        validator.validate(kind, payload, sourceLabel)
      }

      override fun violation(
        kind: FeatureTaskRuntimeWireArtifactKind,
        payload: FeatureTaskRuntimeWorkflowArtifactMap,
        sourceLabel: String,
      ): String? = null
    }
}

fun engineWorkflowGoalRunnerManifestStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  decompositionManifestValidator: DecompositionManifestValidator,
  decompositionManifestStore: DecompositionManifestStore,
  clock: Clock,
  decompositionManifestWriter: DecompositionManifestWriter,
  repositoryRoot: RepositoryRoot,
  planningHydrator: GoalChildPlanningHydratorPort,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
  executionPlanCompatibility: FeatureTaskRuntimeExecutionPlanCompatibility = testExecutionPlanCompatibility(),
  projectStore: ProjectStorePort = FixedProjectStorePort(createTempDirectory("goal-runner-manifest-project-store")),
): GoalRunnerManifestStore {
  val executionAdmission =
    FeatureTaskRuntimeExecutionAdmission(
      executionPlanCompatibility,
      NoopRuntimeDiagnostics,
      ContractFeatureTaskRuntimePhaseOutputMigration(),
      NoopFeatureTaskRuntimeWorkerSupervisor,
    )
  val historyEvidence =
    GoalChildHistoryEvidenceStore(
      workflowSnapshotValidator,
      featureTaskRuntimeWireArtifactValidatorForTest(),
      FeatureTaskRuntimeExecutionPlanSchemaValidator(),
    )
  val qualityExecution = testWorkflowGoalRunnerQualityExecutionStore(database, workflowSnapshotValidator)
  return WorkflowGoalRunnerManifestStore(
    database = database,
    projectionPersistence =
      WorkflowGoalRunnerManifestProjectionPersistence(
        database,
        workflowSnapshotValidator,
        decompositionManifestWriter,
        repositoryRoot,
        decompositionManifestValidator,
        decompositionManifestStore,
      ),
    decompositionManifestValidator = decompositionManifestValidator,
    clock = clock,
    manifestLoader =
      WorkflowGoalRunnerManifestLoader(
        database,
        decompositionManifestValidator,
        decompositionManifestStore,
        clock,
        Random.Default,
        projectStore,
      ),
    repositoryEnclosingRootPort = repositoryEnclosingRootPort,
    executionAdmission = executionAdmission,
    historyEvidence = historyEvidence,
    qualityExecution = qualityExecution,
    childWorkflowPersistence =
      WorkflowGoalRunnerChildWorkflowPersistence(
        planningHydrator,
        decompositionManifestValidator,
        executionAdmission,
        clock,
        repositoryEnclosingRootPort,
        projectStore,
        historyEvidence,
        qualityExecution,
      ),
  )
}

private fun testFeatureTaskRuntimeCrashReconciler(
  database: DatabaseSessionFactory,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  clock: Clock = testHarnessClock,
): FeatureTaskRuntimeCrashReconciler =
  FeatureTaskRuntimeCrashReconciler(
    database,
    workerSupervisor,
    NoopRuntimeDiagnostics,
    clock,
    testExecutionPlanCompatibility(),
    FeatureTaskRuntimeExecutionPlanResolver(
      statusProjectionPhaseStrategies(),
      FeatureTaskRuntimeExecutionPlanCodec(FeatureTaskRuntimeExecutionPlanSchemaValidator()),
      FeatureTaskRuntimeExecutionPlanSchemaValidator(),
      ValidationGateResolver { emptyList() },
      NoopWorkflowGitOperations,
      object : RepoLocalConfigPort {
        override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
          ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
      },
      database,
      testExecutionPlanCompatibility(),
      NoopRuntimeDiagnostics,
    ),
  )

fun testExecutionPlanCompatibility(): FeatureTaskRuntimeExecutionPlanCompatibility {
  val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  return FeatureTaskRuntimeExecutionPlanCompatibility(
    FeatureTaskRuntimeExecutionPlanCodec(validator),
    statusProjectionPhaseStrategies(),
  )
}

fun engineWorkflowGoalRunnerOutcomeStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  gitOperations: WorkflowGitOperations,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  clock: Clock,
  artifactPorts: OutcomeStoreTestArtifactPorts,
  crashReconciler: FeatureTaskRuntimeCrashReconciler =
    testFeatureTaskRuntimeCrashReconciler(database, workerSupervisor, clock),
): WorkflowGoalRunnerOutcomeStore =
  WorkflowGoalRunnerOutcomeStore(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    wireArtifactValidator = artifactPorts.wireArtifactValidator(),
    goalQualityExecution = testWorkflowGoalRunnerQualityExecutionStore(database, workflowSnapshotValidator),
    historyEvidence =
      GoalChildHistoryEvidenceStore(
        workflowSnapshotValidator,
        featureTaskRuntimeWireArtifactValidatorForTest(),
        FeatureTaskRuntimeExecutionPlanSchemaValidator(),
      ),
    gitOperations = gitOperations,
    workerSupervisor = workerSupervisor,
    crashReconciler = crashReconciler,
    blockIncidentCapture = testBlockIncidentCapture(database, clock),
    clock = clock,
  )

fun testWorkflowGoalRunnerQualityExecutionStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
): WorkflowGoalRunnerQualityExecutionStore {
  var invocation = 0
  return WorkflowGoalRunnerQualityExecutionStore(
    database,
    workflowSnapshotValidator,
    featureTaskRuntimeWireArtifactValidatorForTest(),
    IdentifierGeneratorPort { "test-quality-${++invocation}" },
    GoalChildHistoryEvidenceStore(
      workflowSnapshotValidator,
      featureTaskRuntimeWireArtifactValidatorForTest(),
      FeatureTaskRuntimeExecutionPlanSchemaValidator(),
    ),
  )
}

fun engineWorkflowGoalRunnerChildRepairStore(
  database: DatabaseSessionFactory,
  childRepairExecutor: GoalRunnerChildRepairOperations,
  decompositionManifestValidator: DecompositionManifestValidator,
  decompositionManifestWriter: DecompositionManifestWriter,
  decompositionManifestStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
): WorkflowGoalRunnerChildRepairStore =
  WorkflowGoalRunnerChildRepairStore(
    database = database,
    childRepairExecutor = childRepairExecutor,
    decompositionManifestValidator = decompositionManifestValidator,
    decompositionManifestStore = decompositionManifestStore,
    decompositionManifestWriter = decompositionManifestWriter,
  )
