package skillbill.engine.workflow.support

import skillbill.error.shellcontent.legacyProseWorkflowError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode

fun workflowFamilyFor(
  workflowStates: WorkflowStateRepository,
  workflowId: String,
): WorkflowFamily? {
  val featureTaskRow = workflowStates.getFeatureTaskWorkflow(workflowId)
  if (featureTaskRow != null) {
    return when (featureTaskRow.mode) {
      FeatureTaskWorkflowMode.RUNTIME -> WorkflowFamily.TASK_RUNTIME
      FeatureTaskWorkflowMode.PROSE, null -> throw legacyProseWorkflowError(workflowId, featureTaskRow.issueKey)
    }
  }
  return if (workflowStates.get(WorkflowFamily.VERIFY, workflowId) != null) {
    WorkflowFamily.VERIFY
  } else {
    null
  }
}
