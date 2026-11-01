package skillbill.engine.work.eligibility

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.install.model.SupportedAgent
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily

internal fun standaloneLaunchAgent(
  record: StandalonePhaseStatusRecord,
  unit: UnitOfWork,
): String? =
  (record.launchAgentId ?: recordedLaunchAgent(record.workflowId, unit))?.takeIf { it in SupportedAgent.supportedIds }

internal fun recordedLaunchAgent(
  workflowId: String?,
  unit: UnitOfWork,
): String? {
  val workflow = workflowId?.let { unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, it) } ?: return null
  val plan =
    JsonCodec.anyToStringAnyMap(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(workflow.artifacts),
    )
  val assignments = plan?.get(FeatureTaskRuntimeExecutionPlanKeys.STEP_LAUNCH_ASSIGNMENTS) as? List<*> ?: return null
  val records = assignments.mapNotNull(JsonCodec::anyToStringAnyMap)
  val current = records.firstOrNull { it[FeatureTaskRuntimeExecutionPlanKeys.STEP_ID] == workflow.currentStepId }
  return (current ?: records.firstOrNull())?.get(FeatureTaskRuntimeExecutionPlanKeys.AGENT_ID) as? String
}
