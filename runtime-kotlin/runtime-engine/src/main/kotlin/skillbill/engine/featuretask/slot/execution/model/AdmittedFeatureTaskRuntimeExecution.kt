package skillbill.engine.featuretask.slot.execution.model

import skillbill.engine.featuretask.slot.execution.EffectiveGatePolicyInputs
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.contracts.JsonCodec
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

class AdmittedFeatureTaskRuntimeExecution internal constructor(
  val identity: FeatureTaskExecutionIdentity,
  val plan: ResolvedPhaseExecutionPlan,
  val effectiveInputs: EffectiveGatePolicyInputs,
  descriptor: Any,
) {
  private val descriptorJson = JsonCodec.valueToJsonString(descriptor)

  fun requireCurrent(states: WorkflowStateRepository, workflowId: String) {
    if (workflowId != identity.workflowId || states.getFeatureTaskExecutionIdentity(workflowId) != identity) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "admitted route identity changed")
    }
    val row = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
      ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "admitted workflow disappeared")
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(row.toSnapshot().artifacts)
    if (descriptor != JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(descriptorJson)))) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
  }
}
