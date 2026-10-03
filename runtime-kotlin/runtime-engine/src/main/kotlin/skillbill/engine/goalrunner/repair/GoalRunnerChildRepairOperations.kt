package skillbill.engine.goalrunner.repair

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.engine.WorkflowEngine
import java.nio.file.Path
import java.time.Clock

@Inject
class GoalRunnerChildRepairOperations(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val gitOperations: WorkflowGitOperations,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val clock: Clock,
) : GoalRunnerChildRepairRunnerPort {
  private val engine = WorkflowEngine()
  private val workflowPersistence = FeatureTaskRuntimeWorkflowPersistence(database, workflowSnapshotValidator)
  private val wedgeDiagnosis = GoalRunnerChildRepairWedgeDiagnosis(gitOperations, clock)
  private val wedgeApplyLoop =
    GoalRunnerChildRepairWedgeApplyLoop(
      engine,
      workflowPersistence,
      gitOperations,
      wedgeDiagnosis,
      decompositionManifestValidator,
      clock,
    )

  override fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis = wedgeDiagnosis.diagnose(workflowStates, workflowId, issueKey, subtaskId, repoRoot)

  override fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult =
    wedgeApplyLoop.apply(request)
}
