package skillbill.application.workflow.decomposition

import skillbill.application.workflow.service.migrateLegacyGoalRunnerControls
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.withRetriedSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact

fun WorkflowEngine.updateGoalParentForBlockedPhaseRetry(
  unitOfWork: GoalRunnerPersistenceSession,
  childWorkflowId: String,
  childArtifacts: DurableWorkflowArtifacts,
  phaseId: String,
  validator: DecompositionManifestValidator,
): DurableWorkflowArtifacts? {
  val continuation = childArtifacts.goalContinuationArtifact() ?: return null
  val parentWorkflowId = continuation.parentWorkflowId ?: return null
  val parent =
    unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
      ?: invalidGoalRetryProjection(
        "Goal child '$childWorkflowId' references unknown parent workflow '$parentWorkflowId'.",
      )
  val parentManifest =
    parent.decompositionRuntime()
      ?: invalidGoalRetryProjection(
        "Goal parent '$parentWorkflowId' has no decomposition runtime artifact.",
      )
  if (parentManifest.issueKey != continuation.issueKey) {
    invalidGoalRetryProjection(
      "Goal child '$childWorkflowId' issue '${continuation.issueKey}' does not match parent " +
        "issue '${parentManifest.issueKey}'.",
    )
  }
  val retriedManifest =
    parentManifest.withRetriedSubtask(
      subtaskId = continuation.subtaskId,
      workflowId = childWorkflowId,
      lastResumableStep = phaseId,
    )
  val parentInput =
    WorkflowUpdateInput(
      workflowStatus = parent.workflowStatus,
      currentStepId = parent.currentStepId.orEmpty(),
      stepUpdates = null,
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
              validator.encodeManifestWireMap(
                retriedManifest,
                DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
              ),
            ),
          ),
        ),
      sessionId = parent.sessionId.orEmpty(),
      replaceArtifacts = true,
    )
  migrateLegacyGoalRunnerControls(unitOfWork, parent)
  val updatedParent = updateRecord(WorkflowFamily.TASK_RUNTIME.definition, parent, parentInput)
  unitOfWork.workflowStates.saveRecord(
    WorkflowFamily.TASK_RUNTIME,
    updatedParent.toRecord().copy(issueKey = retriedManifest.issueKey),
  )
  return updatedParent.artifacts
}

private fun invalidGoalRetryProjection(reason: String): Nothing = throw InvalidWorkflowStateSchemaError(reason)
