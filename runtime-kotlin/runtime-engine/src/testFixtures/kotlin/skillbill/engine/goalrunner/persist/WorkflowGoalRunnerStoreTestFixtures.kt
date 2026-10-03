package skillbill.engine.goalrunner.persist
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPort
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairOperations
import skillbill.engine.goalrunner.repair.WorkflowGoalRunnerChildRepairStore
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.time.Clock
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
): GoalRunnerManifestStore =
  WorkflowGoalRunnerManifestStore(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    decompositionManifestValidator = decompositionManifestValidator,
    decompositionManifestStore = decompositionManifestStore,
    clock = clock,
    random = Random.Default,
    decompositionManifestWriter = decompositionManifestWriter,
    repositoryRoot = repositoryRoot,
    planningHydrator = planningHydrator,
    repositoryEnclosingRootPort = repositoryEnclosingRootPort,
    executionAdmission =
      FeatureTaskRuntimeExecutionAdmission(
        executionPlanCompatibility,
        NoopRuntimeDiagnostics,
        ContractFeatureTaskRuntimePhaseOutputMigration(),
        planningMigrationForTest(),
        NoopFeatureTaskRuntimeWorkerSupervisor,
      ),
  )

private fun testExecutionPlanCompatibility(): FeatureTaskRuntimeExecutionPlanCompatibility {
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
): WorkflowGoalRunnerOutcomeStore =
  WorkflowGoalRunnerOutcomeStore(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    wireArtifactValidator = artifactPorts.wireArtifactValidator(),
    gitOperations = gitOperations,
    workerSupervisor = workerSupervisor,
    clock = clock,
  )

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
