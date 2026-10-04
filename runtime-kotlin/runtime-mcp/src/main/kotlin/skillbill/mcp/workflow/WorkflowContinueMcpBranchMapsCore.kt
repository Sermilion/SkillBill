package skillbill.mcp.workflow

import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys

internal fun WorkflowContinueResult.UnknownWorkflow.toUnknownWorkflowMcpMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    LifecycleTelemetryPayloadKeys.ERROR to "Unknown workflow_id '$workflowId'.",
    WorkflowWirePayloadKeys.DB_PATH to dbPath,
  )

internal fun WorkflowContinueResult.Error.toErrorMcpMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    LifecycleTelemetryPayloadKeys.ERROR to error,
    WorkflowWirePayloadKeys.DB_PATH to dbPath,
  )
