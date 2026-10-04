package skillbill.application.workflow.decomposition

import skillbill.application.workflow.service.ContinuationStepResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.isGoalContinuationChildWorkflow
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact

internal data class PendingDecompositionProjection(
  val ownerWorkflowId: String,
  val artifacts: DurableWorkflowArtifacts,
)

internal fun ContinuationStepResult.withPendingProjection(
  ownerWorkflowId: String,
  artifacts: DurableWorkflowArtifacts,
): ContinuationStepResult =
  copy(
    projectionOwnerWorkflowId = ownerWorkflowId,
    projectionArtifacts = artifacts,
  )

internal fun resolveDecompositionProjectionOwner(
  record: WorkflowStateSnapshot,
  unitOfWork: UnitOfWork,
): String? {
  val manifest = record.decompositionRuntime() ?: return null
  return if (record.isGoalContinuationChildWorkflow()) {
    unitOfWork.workflowStates.findDecomposedParentWorkflowForRuntime(manifest)
      ?.workflowId
      ?: goalContinuationParentWorkflowId(record.artifacts)
  } else {
    record.workflowId
  }
}

internal fun goalContinuationParentWorkflowIdForSettlement(artifacts: Map<String, Any?>): String? =
  goalContinuationParentWorkflowId(artifacts)

private fun goalContinuationParentWorkflowId(artifacts: Map<String, Any?>): String? {
  return DurableWorkflowArtifacts.fromMap(artifacts).goalContinuationArtifact()
    ?.parentWorkflowId
    ?.takeIf(String::isNotBlank)
}
