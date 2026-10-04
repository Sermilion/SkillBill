package skillbill.application.workflow.decomposition

import skillbill.error.shellcontent.legacyProseWorkflowError
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus

internal val IMPLEMENT_TERMINAL_STATUSES: Set<WorkflowStatus> = WorkflowStatus.terminalStatuses

fun WorkflowStateRecord.requireRuntimeModeForEngineWrite() {
  if (mode != FeatureTaskWorkflowMode.RUNTIME) {
    throw legacyProseWorkflowError(workflowId, issueKey)
  }
}
