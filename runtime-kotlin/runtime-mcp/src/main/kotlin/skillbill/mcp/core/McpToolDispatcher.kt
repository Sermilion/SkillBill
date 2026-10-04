package skillbill.mcp.core

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.error.core.ReviewAttributionFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeRegenerationRefusal
import skillbill.error.featuretask.PhaseSlotFailureCode
import skillbill.error.learning.InvalidLearningSourceReason
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArgumentFailureCode
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys
import skillbill.mcp.shared.invalidMcpToolArgument
import skillbill.mcp.telemetry.TELEMETRY_EVENT_CONTRACT_VERSION
import skillbill.mcp.telemetry.TelemetryEventSchemaValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import kotlin.coroutines.cancellation.CancellationException

internal object McpToolDispatcher {
  fun dispatch(
    toolName: String,
    rawArguments: Map<String, Any?>,
    component: McpComponent,
  ): Map<String, Any?> =
    runCatching { mcpToolResult(invoke(toolName, rawArguments, component), isError = false) }
      .getOrElse { error ->
        when {
          error is CancellationException -> throw error
          error.uncapturedAtMcp() -> mcpToolErrorResult(toolName, error)
          error is Exception -> {
            recordCaptureFailure(
              workflowPhase = toolName,
              capture = { component.telemetryService.captureException(toolName, error) },
              diagnostics = component.runtimeDiagnostics,
            )
            mcpToolErrorResult(toolName, error)
          }
          else -> throw error
        }
      }

  private fun Throwable.uncapturedAtMcp(): Boolean =
    isShellContentContractFailure() ||
      (this as? SkillBillRuntimeException)?.code is FeatureTaskRuntimeRegenerationRefusal ||
      (this as? SkillBillRuntimeException)?.code is InvalidLearningSourceReason ||
      (this as? SkillBillRuntimeException)?.code is ReviewAttributionFailureCode ||
      (this as? SkillBillRuntimeException)?.code is McpToolArgumentFailureCode ||
      (this as? SkillBillRuntimeException)?.code == PhaseSlotFailureCode.INVALID_STRATEGY_COMPOSITION ||
      this is IllegalArgumentException ||
      this is IllegalStateException

  private fun invoke(
    toolName: String,
    rawArguments: Map<String, Any?>,
    component: McpComponent,
  ): Map<String, Any?> {
    val tool =
      McpToolRegistry.toolNamed(toolName)
        ?: throw invalidMcpToolArgument(
          toolName = toolName,
          argumentKey = "tool",
          detail = "unknown tool",
        )
    tool.runtimeOwnedArgumentKeys.firstOrNull(rawArguments::containsKey)?.let { key ->
      throw invalidMcpToolArgument(tool.name, key, "is runtime-owned")
    }
    val arguments = tool.normalize?.invoke(rawArguments) ?: rawArguments
    TelemetryEventSchemaValidator.validate(
      envelope = telemetryEnvelope(tool.name, arguments),
      eventName = tool.name,
    )
    return tool.handler.invoke(McpToolArguments(tool.name, arguments), component)
  }

  internal fun telemetryEnvelope(
    toolName: String,
    arguments: Map<String, Any?>,
  ): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      LifecycleTelemetryPayloadKeys.EVENT_NAME to toolName,
      SharedPayloadKeys.CONTRACT_VERSION to TELEMETRY_EVENT_CONTRACT_VERSION,
    ) +
      arguments.filterKeys {
        it != LifecycleTelemetryPayloadKeys.EVENT_NAME && it != SharedPayloadKeys.CONTRACT_VERSION
      }
}

internal fun normalizeQualityCheckFinished(arguments: Map<String, Any?>): Map<String, Any?> {
  val stack =
    arguments[LifecycleTelemetryPayloadKeys.DETECTED_STACK]?.toString()?.trim().orEmpty().ifBlank { "unknown" }
  val fallback = arguments[LifecycleTelemetryPayloadKeys.FALLBACK] == true
  return arguments.toMutableMap().apply {
    put(
      LifecycleTelemetryPayloadKeys.ROUTED_SKILL,
      normalizeQualityCheckRoutedSkill(arguments[LifecycleTelemetryPayloadKeys.ROUTED_SKILL]?.toString()),
    )
    put(LifecycleTelemetryPayloadKeys.DETECTED_STACK, stack)
    put(LifecycleTelemetryPayloadKeys.FALLBACK, fallback)
    val fallbackReason =
      arguments[LifecycleTelemetryPayloadKeys.FALLBACK_REASON]?.toString()?.takeIf(String::isNotBlank)
    if (fallback && fallbackReason != null) {
      put(LifecycleTelemetryPayloadKeys.FALLBACK_REASON, fallbackReason)
    }
  }
}

internal fun recordCaptureFailure(
  workflowPhase: String,
  capture: () -> Unit,
  diagnostics: RuntimeDiagnostics,
) {
  runCatching { capture() }.onFailure { captureError ->
    diagnostics.error("MCP telemetry capture failed for tool '$workflowPhase'.", captureError)
  }
}

internal fun mcpToolErrorResult(
  toolName: String,
  error: Throwable,
): Map<String, Any?> =
  mcpToolResult(
    mapOf(
      SharedPayloadKeys.STATUS to "error",
      McpToolPayloadKeys.TOOL to toolName,
      LifecycleTelemetryPayloadKeys.ERROR to error.message.orEmpty(),
    ),
    isError = true,
  )

private fun mcpToolResult(
  payload: Map<String, Any?>,
  isError: Boolean,
): Map<String, Any?> =
  linkedMapOf(
    McpToolPayloadKeys.CONTENT to
      listOf(
        mapOf(
          McpToolPayloadKeys.TYPE to "text",
          McpToolPayloadKeys.TEXT to JsonCodec.mapToJsonString(payload),
        ),
      ),
    McpToolPayloadKeys.IS_ERROR to isError,
  )

private fun normalizeQualityCheckRoutedSkill(rawValue: String?): String {
  val value = rawValue?.trim().orEmpty()
  if (value.isEmpty()) return "unrouted"
  val withoutNamespace = value.substringAfter(':')
  return if (
    withoutNamespace != value &&
    withoutNamespace.matches(Regex("^[a-z0-9][a-z0-9-]*$")) &&
    value.substringBefore(':').matches(Regex("^[A-Za-z][A-Za-z0-9_-]*$"))
  ) {
    withoutNamespace
  } else {
    value
  }
}
