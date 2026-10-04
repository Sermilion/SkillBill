package skillbill.engine.goalrunner.repair

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.clearDecompositionManifestProjectionFailure
import skillbill.application.decomposition.persistDecompositionManifestProjectionFailure
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeRepairRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.engine.WorkflowEngine

class WorkflowGoalRunnerChildRepairStore
  @Inject
  constructor(
    private val database: DatabaseSessionFactory,
    private val childRepairExecutor: GoalRunnerChildRepairRunnerPort,
    private val decompositionManifestValidator: DecompositionManifestValidator,
    private val decompositionManifestStore: DecompositionManifestStore,
    private val decompositionManifestWriter: DecompositionManifestWriter,
  ) : GoalRunnerChildRepairStore {
    private val engine = WorkflowEngine()

    override fun diagnoseChildWedges(request: GoalRunnerChildWedgeDiagnosisRequest): GoalRunnerChildWedgeDiagnosis =
      database.read { unitOfWork ->
        childRepairExecutor.diagnose(
          workflowStates = unitOfWork.workflowStates,
          workflowId = request.workflowId,
          issueKey = request.issueKey,
          subtaskId = request.subtaskId,
          repoRoot = request.repoRoot,
        )
      }

    override fun applyChildWedgeRepairs(request: GoalRunnerChildWedgeRepairRequest): GoalRunnerChildRepairApplyResult {
      val result =
        database.transaction { unitOfWork ->
          childRepairExecutor.apply(
            GoalRunnerChildRepairApplyRequest(
              unitOfWork = unitOfWork,
              workflowId = request.workflowId,
              issueKey = request.issueKey,
              subtaskId = request.subtaskId,
              wedgeClasses = request.wedgeClasses,
              repoRoot = request.repoRoot,
              wedgeFindings = request.wedgeFindings,
            ),
          )
        }
      result.manifestProjectionArtifacts?.let { artifacts ->
        when (
          val outcome =
            decompositionManifestWriter.writeProjectionFromWorkflowState(
              repoRoot = request.repoRoot,
              artifacts = artifacts,
              validator = decompositionManifestValidator,
              fileStore = decompositionManifestStore,
            )
        ) {
          is DecompositionManifestProjectionOutcome.Failed ->
            database.transaction { unitOfWork ->
              persistDecompositionManifestProjectionFailure(
                engine,
                unitOfWork,
                request.workflowId,
                outcome,
              )
            }
          is DecompositionManifestProjectionOutcome.Written ->
            database.transaction { unitOfWork ->
              clearDecompositionManifestProjectionFailure(engine, unitOfWork, request.workflowId)
            }
          DecompositionManifestProjectionOutcome.Absent -> Unit
        }
      }
      return result
    }
  }
