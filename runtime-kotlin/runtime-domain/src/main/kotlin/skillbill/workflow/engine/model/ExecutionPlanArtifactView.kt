package skillbill.workflow.engine.model

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys

object ExecutionPlanArtifactView {
  fun definitionId(artifacts: DurableWorkflowArtifacts): String? =
    JsonCodec.anyToStringAnyMap(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts))
      ?.let { plan -> JsonCodec.anyToStringAnyMap(plan[FeatureTaskRuntimeExecutionPlanKeys.DEFINITION]) }
      ?.get(FeatureTaskRuntimeExecutionPlanKeys.ID) as? String
}
