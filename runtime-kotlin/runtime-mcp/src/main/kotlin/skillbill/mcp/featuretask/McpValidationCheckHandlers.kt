package skillbill.mcp.featuretask

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.persistence.RuntimeMcpEnvironmentKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys
import skillbill.engine.featuretask.validation.check.ValidationCheckOutcomeResult
import skillbill.engine.featuretask.validation.check.ValidationCheckReport
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckOutcome

internal const val VALIDATION_CHECK_PROTOCOL: String =
  "Report one validation check of the running validation phase. Call planned once per check before " +
    "running any check. Call started right before you run a check and finished with its outcome right " +
    "after it ends. Report a check you will not run as finished with outcome skipped, without a start. " +
    "Never send times; the runtime records them. Keep check_id the same for every event of one check."

internal fun validationCheckInvocationId(component: McpComponent): String? =
  component.resolvedEnvironmentContext.environment[RuntimeMcpEnvironmentKeys.VALIDATION_INVOCATION_ID]
    ?.takeIf(String::isNotBlank)

internal fun validationCheck(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  val result =
    component.validationCheckRecorder.record(
      ValidationCheckReport(
        invocationId = validationCheckInvocationId(component),
        event = ValidationCheckEvent.fromWire(arguments.optionalString(ValidationCheckPayloadKeys.EVENT)),
        checkId = arguments.string(ValidationCheckPayloadKeys.CHECK_ID),
        name = arguments.string(ValidationCheckPayloadKeys.NAME),
        command = arguments.string(ValidationCheckPayloadKeys.COMMAND),
        workingDir = arguments.string(ValidationCheckPayloadKeys.WORKING_DIR),
        outcome = ValidationCheckOutcome.fromWire(arguments.optionalString(ValidationCheckPayloadKeys.OUTCOME)),
        exitCode = arguments.optionalInt(ValidationCheckPayloadKeys.EXIT_CODE),
        note = arguments.optionalString(ValidationCheckPayloadKeys.NOTE),
      ),
    )
  return when (result) {
    is ValidationCheckOutcomeResult.Recorded ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to ValidationCheckResultStatus.RECORDED.wireValue,
        ValidationCheckPayloadKeys.SEQUENCE to result.sequence,
        SharedPayloadKeys.ATTEMPT to result.attempt,
      )
    is ValidationCheckOutcomeResult.Rejected ->
      errorResult(ValidationCheckResultStatus.REJECTED, result.reason.wireValue)
    is ValidationCheckOutcomeResult.Failed -> errorResult(ValidationCheckResultStatus.FAILED, result.reason)
  }
}

private fun errorResult(
  status: ValidationCheckResultStatus,
  reason: String,
): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status.wireValue,
    UpdateCheckPayloadKeys.REASON to reason,
    McpToolPayloadKeys.IS_ERROR to true,
  )
