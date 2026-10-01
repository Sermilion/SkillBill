package skillbill.application.decomposition

import skillbill.application.decomposition.model.DecompositionManifestProjectionFailurePersistence
import skillbill.application.decomposition.model.RetryDecompositionManifestProjectionArgs
import skillbill.contracts.decomposition.DecompositionManifestProjectionFailurePayloadKeys
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput

internal fun retryDecompositionManifestProjectionFromAuthoritativeState(
  args: RetryDecompositionManifestProjectionArgs,
): DecompositionManifestProjectionOutcome {
  val database = args.database
  val engine = args.engine
  val decompositionManifestWriter = args.decompositionManifestWriter
  val decompositionManifestValidator = args.decompositionManifestValidator
  val decompositionManifestStore = args.decompositionManifestStore
  val repoRoot = args.repoRoot
  val workflowId = args.workflowId
  val artifacts =
    database.read { unitOfWork ->
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.artifacts
    } ?: return DecompositionManifestProjectionOutcome.Absent
  val outcome =
    decompositionManifestWriter.writeProjectionFromWorkflowState(
      repoRoot = repoRoot,
      artifacts = artifacts,
      validator = decompositionManifestValidator,
      fileStore = decompositionManifestStore,
    )
  if (outcome is DecompositionManifestProjectionOutcome.Written) {
    val cleared =
      database.transaction { unitOfWork ->
        clearDecompositionManifestProjectionFailure(engine, unitOfWork, workflowId)
      }
    if (cleared == DecompositionManifestProjectionFailurePersistence.OWNER_ABSENT) {
      return DecompositionManifestProjectionOutcome.Absent
    }
  }
  return outcome
}

fun persistDecompositionManifestProjectionFailure(
  engine: WorkflowEngine,
  unitOfWork: UnitOfWork,
  workflowId: String,
  outcome: DecompositionManifestProjectionOutcome.Failed,
): DecompositionManifestProjectionFailurePersistence {
  val family = WorkflowFamily.TASK_RUNTIME
  val existing =
    unitOfWork.workflowStates.get(family, workflowId)
      ?: return DecompositionManifestProjectionFailurePersistence.OWNER_ABSENT
  val updated =
    engine.updateRecord(
      family.definition,
      existing,
      WorkflowUpdateInput(
        workflowStatus = existing.workflowStatus,
        currentStepId = existing.currentStepId,
        stepUpdates = null,
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE.entry(
                mapOf(
                  DecompositionManifestProjectionFailurePayloadKeys.OPERATION to outcome.operation,
                  DecompositionManifestProjectionFailurePayloadKeys.TARGET_PATH to outcome.targetPath,
                ),
              ),
            ),
          ),
        sessionId = existing.sessionId.orEmpty(),
      ),
    )
  unitOfWork.workflowStates.save(family, updated)
  return DecompositionManifestProjectionFailurePersistence.PERSISTED
}

fun clearDecompositionManifestProjectionFailure(
  engine: WorkflowEngine,
  unitOfWork: UnitOfWork,
  workflowId: String,
): DecompositionManifestProjectionFailurePersistence {
  val family = WorkflowFamily.TASK_RUNTIME
  val existing =
    unitOfWork.workflowStates.get(family, workflowId)
      ?: return DecompositionManifestProjectionFailurePersistence.OWNER_ABSENT
  val updated =
    engine.updateRecord(
      family.definition,
      existing,
      WorkflowUpdateInput(
        workflowStatus = existing.workflowStatus,
        currentStepId = existing.currentStepId,
        stepUpdates = null,
        artifactsPatch =
          WorkflowArtifactPatch.from(
            existing.artifacts.toMutableMap().apply {
              DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE.removeFrom(this)
            },
          ),
        sessionId = existing.sessionId.orEmpty(),
        replaceArtifacts = true,
      ),
    )
  unitOfWork.workflowStates.save(family, updated)
  return DecompositionManifestProjectionFailurePersistence.PERSISTED
}
