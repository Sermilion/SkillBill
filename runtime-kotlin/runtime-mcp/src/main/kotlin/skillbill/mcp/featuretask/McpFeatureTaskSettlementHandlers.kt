package skillbill.mcp.featuretask

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.mcp.McpToolPayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAcknowledgment
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal fun featureTaskPhaseComplete(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.featureTaskPhaseSettlementService.complete(
    FeatureTaskPhaseSettlementCompleteRequest(
      workflowId = arguments.string(SharedPayloadKeys.WORKFLOW_ID),
      phaseId = arguments.string(SharedPayloadKeys.PHASE_ID),
      attempt = arguments.requiredAttempt(),
      value = arguments.string(SharedPayloadKeys.VALUE),
      prompt = arguments.optionalString(SharedPayloadKeys.PROMPT),
      summary = arguments.optionalString(SharedPayloadKeys.SUMMARY),
      verdict = arguments.optionalString(SharedPayloadKeys.VERDICT),
    ),
  ).toWireMap()

internal fun featureTaskPhaseBlock(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.featureTaskPhaseSettlementService.block(
    FeatureTaskPhaseSettlementBlockRequest(
      workflowId = arguments.string(SharedPayloadKeys.WORKFLOW_ID),
      phaseId = arguments.string(SharedPayloadKeys.PHASE_ID),
      attempt = arguments.requiredAttempt(),
      reason = arguments.string(McpToolPayloadKeys.REASON),
      failureDisposition =
        arguments.optionalString(SharedPayloadKeys.FAILURE_DISPOSITION)
          ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION.wireValue,
      verdict = arguments.optionalString(SharedPayloadKeys.VERDICT),
    ),
  ).toWireMap()

private fun McpToolArguments.requiredAttempt(): Int =
  optionalInt(McpToolPayloadKeys.ATTEMPT) ?: invalid(McpToolPayloadKeys.ATTEMPT, "is required")

private fun FeatureTaskPhaseSettlementAcknowledgment.toWireMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    SharedPayloadKeys.PHASE_ID to phaseId,
    SharedPayloadKeys.ATTEMPT to attempt,
    McpToolPayloadKeys.KIND to kind.wireValue,
    McpToolPayloadKeys.ENVELOPE to envelope,
  )
