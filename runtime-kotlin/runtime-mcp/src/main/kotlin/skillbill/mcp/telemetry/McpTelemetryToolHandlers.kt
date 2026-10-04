package skillbill.mcp.telemetry

import skillbill.contracts.telemetry.TelemetryProxyPayloadKeys
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition

private const val VERIFY_WORKFLOW_ALIAS: String = "verify"

internal fun telemetryRemoteStats(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.telemetryService.remoteStats(
    RemoteStatsRequest(
      workflow = arguments.remoteStatsWorkflow(),
      since = arguments.optionalString(TelemetryProxyPayloadKeys.SINCE).orEmpty(),
      dateFrom = arguments.optionalString(TelemetryProxyPayloadKeys.DATE_FROM).orEmpty(),
      dateTo = arguments.optionalString(TelemetryProxyPayloadKeys.DATE_TO).orEmpty(),
      groupBy = arguments.optionalString(TelemetryProxyPayloadKeys.GROUP_BY).orEmpty(),
    ),
  ).toMcpMap()

internal fun telemetryProxyCapabilities(component: McpComponent): Map<String, Any?> =
  component.telemetryService.capabilities().toMcpMap()

private fun McpToolArguments.remoteStatsWorkflow(): String {
  val requested = string(TelemetryProxyPayloadKeys.WORKFLOW)
  return if (requested == VERIFY_WORKFLOW_ALIAS) {
    FeatureVerifyWorkflowDefinition.definition.skillName
  } else {
    requested
  }
}
