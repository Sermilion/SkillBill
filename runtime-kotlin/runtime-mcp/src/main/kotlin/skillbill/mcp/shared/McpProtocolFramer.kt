package skillbill.mcp.shared

import skillbill.contracts.JsonCodec
import skillbill.di.core.SkillBillVersion

internal object McpProtocolFramer {
  const val JSON_RPC_KEY: String = "jsonrpc"
  const val JSON_RPC_VERSION: String = "2.0"
  const val ID_KEY: String = "id"
  const val METHOD_KEY: String = "method"
  const val PARAMS_KEY: String = "params"
  const val RESULT_KEY: String = "result"
  const val ERROR_KEY: String = "error"
  const val CODE_KEY: String = "code"
  const val MESSAGE_KEY: String = "message"
  const val PROTOCOL_VERSION_KEY: String = "protocolVersion"
  const val TOOLS_KEY: String = "tools"
  const val CAPABILITIES_KEY: String = "capabilities"
  const val SERVER_INFO_KEY: String = "serverInfo"
  const val LIST_CHANGED_KEY: String = "listChanged"
  const val NAME_KEY: String = "name"
  const val VERSION_KEY: String = "version"
  const val DESCRIPTION_KEY: String = "description"
  const val INPUT_SCHEMA_KEY: String = "inputSchema"
  const val SCHEMA_PROPERTIES_KEY: String = "properties"
  const val SCHEMA_REQUIRED_KEY: String = "required"
  const val SCHEMA_ENUM_KEY: String = "enum"
  const val SCHEMA_DEFS_KEY: String = "\$defs"
  const val SCHEMA_REF_KEY: String = "\$ref"
  const val SCHEMA_CONST_KEY: String = "const"
  const val INSTRUCTIONS_KEY: String = "instructions"
  const val MCP_PROTOCOL_VERSION: String = "2025-11-25"
  const val PARSE_ERROR: Int = -32700
  const val METHOD_NOT_FOUND: Int = -32601
  const val INTERNAL_ERROR: Int = -32603

  fun initialize(
    serverName: String,
    instructions: String? = null,
  ): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      PROTOCOL_VERSION_KEY to MCP_PROTOCOL_VERSION,
      CAPABILITIES_KEY to mapOf(TOOLS_KEY to mapOf(LIST_CHANGED_KEY to false)),
      SERVER_INFO_KEY to mapOf(NAME_KEY to serverName, VERSION_KEY to SkillBillVersion.VALUE),
    ).apply { instructions?.let { put(INSTRUCTIONS_KEY, it) } }

  fun toolsList(tools: List<Map<String, Any?>>): Map<String, Any?> = mapOf(TOOLS_KEY to tools)

  fun successResponse(
    id: Any?,
    result: Map<String, Any?>,
  ): String =
    JsonCodec.mapToJsonString(
      linkedMapOf(JSON_RPC_KEY to JSON_RPC_VERSION, ID_KEY to id, RESULT_KEY to result),
    )

  fun errorResponse(
    id: Any?,
    code: Int,
    message: String,
  ): String =
    JsonCodec.mapToJsonString(
      linkedMapOf(
        JSON_RPC_KEY to JSON_RPC_VERSION,
        ID_KEY to id,
        ERROR_KEY to mapOf(CODE_KEY to code, MESSAGE_KEY to message),
      ),
    )
}
