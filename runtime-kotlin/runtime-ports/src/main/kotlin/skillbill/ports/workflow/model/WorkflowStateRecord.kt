package skillbill.ports.workflow.model

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus

data class WorkflowStateRecord(
  val workflowId: String,
  val sessionId: String,
  val workflowName: String,
  val contractVersion: String,
  val workflowStatus: String,
  val currentStepId: String,
  val stepsJson: String,
  val artifactsJson: String,
  val startedAt: String?,
  val updatedAt: String?,
  val finishedAt: String?,
  val mode: FeatureTaskWorkflowMode? = null,
  val implementationSkill: String? = null,
  val issueKey: String? = null,
  val stateEnteredAt: String? = null,
  val stateEnteredAtEstimated: Boolean = false,
) {
  companion object {
    internal fun requiredWorkflowStatus(value: String): WorkflowStatus =
      WorkflowStatus.fromWire(value)
        ?: throw invalidWorkflowStateSchemaError(
          "Workflow state workflow_status has unsupported value '$value'.",
        )
  }
}
