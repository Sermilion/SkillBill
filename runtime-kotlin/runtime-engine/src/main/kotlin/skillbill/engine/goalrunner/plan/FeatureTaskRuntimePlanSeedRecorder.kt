package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanSeedFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSeed

@Inject
class FeatureTaskRuntimePlanSeedRecorder(
  private val database: DatabaseSessionFactory,
) {
  private val engine: WorkflowEngine = WorkflowEngine()

  fun record(
    workflowId: String,
    seed: FeatureTaskRuntimePlanSeed,
  ) {
    database.transaction { unitOfWork ->
      val record =
        checkNotNull(unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)) {
          "Plan workflow '$workflowId' is not open."
        }
      val updated =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch =
              WorkflowArtifactPatch.from(
                mapOf(
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_SEED.entry(seed.asWorkflowArtifactEntry()),
                ),
              ),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
    }
  }

  fun read(workflowId: String): FeatureTaskRuntimePlanSeed? =
    database.read { unitOfWork ->
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?.artifacts
        ?.toMap()
        ?.let(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_SEED::value)
        ?.let(::decodePlanSeedFromArtifact)
    }
}
