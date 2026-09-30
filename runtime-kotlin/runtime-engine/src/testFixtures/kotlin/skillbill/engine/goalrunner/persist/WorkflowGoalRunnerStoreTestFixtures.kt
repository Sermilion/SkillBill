package skillbill.engine.goalrunner.persist
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairOperations
import skillbill.engine.goalrunner.repair.WorkflowGoalRunnerChildRepairStore
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.time.Clock
import kotlin.random.Random

data class OutcomeStoreTestArtifactPorts(
  val goalObservabilityEventValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val goalProgressEventValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator = realFeatureTaskRuntimePhaseOutputValidator,
  val decompositionManifestStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
)

fun engineWorkflowGoalRunnerManifestStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  decompositionManifestValidator: DecompositionManifestValidator,
  decompositionManifestStore: DecompositionManifestStore,
  clock: Clock,
  decompositionManifestWriter: DecompositionManifestProjectionWriter,
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
    executionAdmission = FeatureTaskRuntimeExecutionAdmission(executionPlanCompatibility, NoopRuntimeDiagnostics),
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
    goalObservabilityEventValidator = artifactPorts.goalObservabilityEventValidator,
    goalProgressEventValidator = artifactPorts.goalProgressEventValidator,
    gitOperations = gitOperations,
    phaseOutputValidator = artifactPorts.phaseOutputValidator,
    workerSupervisor = workerSupervisor,
    clock = clock,
  )

fun engineWorkflowGoalRunnerChildRepairStore(
  database: DatabaseSessionFactory,
  childRepairExecutor: GoalRunnerChildRepairOperations,
  decompositionManifestValidator: DecompositionManifestValidator,
  decompositionManifestWriter: DecompositionManifestProjectionWriter,
  decompositionManifestStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
): WorkflowGoalRunnerChildRepairStore =
  WorkflowGoalRunnerChildRepairStore(
    database = database,
    childRepairExecutor = childRepairExecutor,
    decompositionManifestValidator = decompositionManifestValidator,
    decompositionManifestStore = decompositionManifestStore,
    decompositionManifestWriter = decompositionManifestWriter,
  )
