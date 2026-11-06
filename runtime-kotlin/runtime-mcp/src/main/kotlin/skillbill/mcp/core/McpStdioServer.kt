package skillbill.mcp.core

import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonCodec
import skillbill.mcp.featuretask.VALIDATION_CHECK_PROTOCOL
import skillbill.mcp.featuretask.validationCheckInvocationId
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpProtocolFramer

internal object McpStdioServer {
  internal fun run(component: McpComponent) {
    generateSequence(::readlnOrNull).forEach { line ->
      handleLine(line, component)?.let(::println)
    }
  }

  internal fun handleLine(
    line: String,
    component: McpComponent,
  ): String? {
    val message = JsonCodec.parseObjectOrNull(line)
    val id = message?.get(McpProtocolFramer.ID_KEY)
    val method =
      message?.get(McpProtocolFramer.METHOD_KEY)
        ?.let(JsonCodec::jsonElementToValue)?.toString().orEmpty()
    return when {
      message == null -> McpProtocolFramer.errorResponse(null, McpProtocolFramer.PARSE_ERROR, "Parse error")
      id == null -> null
      method == "initialize" -> McpProtocolFramer.successResponse(id, initialize(component))
      method == "ping" -> McpProtocolFramer.successResponse(id, emptyMap())
      method == "tools/list" ->
        McpProtocolFramer.successResponse(
          id,
          McpProtocolFramer.toolsList(
            McpToolRegistry.tools.filter { it.advertised(component) }.map(McpTool::toPayload),
          ),
        )
      method == "tools/call" -> {
        val params = message.arguments()
        McpProtocolFramer.successResponse(
          id,
          McpToolDispatcher.dispatch(
            toolName = params["name"]?.toString().orEmpty(),
            rawArguments = JsonCodec.anyToStringAnyMap(params["arguments"]).orEmpty(),
            component = component,
          ),
        )
      }
      else ->
        McpProtocolFramer.errorResponse(
          id,
          McpProtocolFramer.METHOD_NOT_FOUND,
          "Method not found: $method",
        )
    }
  }

  private fun initialize(component: McpComponent): Map<String, Any?> {
    val invocationId = validationCheckInvocationId(component) ?: return McpProtocolFramer.initialize("skill-bill")
    component.validationLiveChecks.markServerStarted(invocationId)
    return McpProtocolFramer.initialize("skill-bill", VALIDATION_CHECK_PROTOCOL)
  }

  private fun JsonObject.arguments(): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(this[McpProtocolFramer.PARAMS_KEY]?.let(JsonCodec::jsonElementToValue)).orEmpty()
}
