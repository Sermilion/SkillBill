package skillbill.mcp.featuretask

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAckStatus
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAcknowledgment
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementPayloadKeys
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaim
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
      noChange = arguments.optionalMap(FeatureTaskRuntimeNoChangeClaim.KEY),
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
      reason = arguments.string(UpdateCheckPayloadKeys.REASON),
      failureDisposition =
        arguments.optionalString(SharedPayloadKeys.FAILURE_DISPOSITION)
          ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION.wireValue,
      verdict = arguments.optionalString(SharedPayloadKeys.VERDICT),
      validationEvidence = arguments.optionalMap(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE),
    ),
  ).toWireMap()

private fun McpToolArguments.requiredAttempt(): Int =
  optionalInt(SharedPayloadKeys.ATTEMPT) ?: invalid(SharedPayloadKeys.ATTEMPT, "is required")

private fun FeatureTaskPhaseSettlementAcknowledgment.toWireMap(): Map<String, Any?> {
  val wire =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.STATUS to status.wireValue,
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      SharedPayloadKeys.PHASE_ID to phaseId,
      SharedPayloadKeys.ATTEMPT to attempt,
      FeatureTaskPhaseSettlementPayloadKeys.KIND to kind.wireValue,
    )
  val settledEnvelope = envelope
  if (settledEnvelope != null) {
    wire[McpToolPayloadKeys.ENVELOPE] = settledEnvelope
  }
  val code = diagnosticCode
  if (code != null) {
    wire[McpToolPayloadKeys.DIAGNOSTIC_CODE] = code
  }
  if (status != FeatureTaskPhaseSettlementAckStatus.OK) {
    wire[McpToolPayloadKeys.IS_ERROR] = true
  }
  return wire
}
