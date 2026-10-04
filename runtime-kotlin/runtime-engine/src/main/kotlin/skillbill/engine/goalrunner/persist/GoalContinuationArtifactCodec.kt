package skillbill.engine.goalrunner.persist

import skillbill.contracts.JsonCodec
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun goalReviewEmissionEnvelope(rawResult: String): FeatureTaskRuntimeWorkflowArtifactMap {
  if (JsonCodec.parseObjectOrNull(rawResult.trim()) == null) {
    return FeatureTaskRuntimeWorkflowArtifactMap.from(emptyMap<String, Any?>())
  }
  return NormalizedFeatureTaskRuntimePhaseOutput
    .fromEnvelopeText(rawResult, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    .envelopeWireMap()
}

fun taskRuntimeRecordOrNull(
  workflowStates: WorkflowStateRepository,
  workflowId: String,
): WorkflowStateSnapshot? =
  if (workflowStates.getFeatureTaskWorkflow(workflowId)?.mode == FeatureTaskWorkflowMode.RUNTIME) {
    workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
  } else {
    null
  }

fun featureTaskRecordForLegacyControls(
  workflowStates: WorkflowStateRepository,
  workflowId: String,
): WorkflowStateSnapshot? = workflowStates.getFeatureTaskWorkflow(workflowId)?.toSnapshot()
