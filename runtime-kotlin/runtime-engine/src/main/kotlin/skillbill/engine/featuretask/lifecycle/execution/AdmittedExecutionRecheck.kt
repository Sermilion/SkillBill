package skillbill.engine.featuretask.lifecycle.execution

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution

  fun AdmittedFeatureTaskRuntimeExecution.requireCurrent(states: WorkflowStateRepository, workflowId: String) {
    if (workflowId != identity.workflowId || states.getFeatureTaskExecutionIdentity(workflowId) != identity) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "admitted route identity changed")
    }
    val row = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
      ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "admitted workflow disappeared")
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(row.toSnapshot().artifacts)
    if (descriptor != JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(this.descriptorJson)))) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
  }
