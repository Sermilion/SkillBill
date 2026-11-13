package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ChatModelKeys {
  const val ID = "id"
  const val METHOD = "method"
  const val PARAMS = "params"
  const val JSONRPC = "jsonrpc"
  const val RESULT = "result"
  const val ERROR = "error"
  const val ERROR_CODE = "code"
  const val ERROR_MESSAGE = "message"
  const val TYPE = "type"
  const val REQUEST = "request"
  const val REQUEST_ID = "request_id"
  const val SUBTYPE = "subtype"
  const val RESPONSE = "response"
  const val MODELS = "models"
  const val DATA = "data"
  const val MODEL = "model"
  const val VALUE = "value"
  const val DISPLAY_NAME = "displayName"
  const val NAME = "name"
  const val NEXT_CURSOR = "nextCursor"
  const val CURSOR = "cursor"
  const val LIMIT = "limit"
  const val CLIENT_INFO = "clientInfo"
  const val VERSION = "version"
  const val PROTOCOL_VERSION = "protocolVersion"
  const val CLIENT_CAPABILITIES = "clientCapabilities"
  const val CWD = "cwd"
  const val MCP_SERVERS = "mcpServers"
  const val AVAILABLE_MODELS = "availableModels"
  const val MODEL_ID = "modelId"
  const val CONFIG_OPTIONS = "configOptions"
  const val CATEGORY = "category"
  const val OPTIONS = "options"
  const val CONFIG_ID = "configId"
  const val CURRENT_VALUE = "currentValue"
  const val SESSION_ID = "sessionId"
  const val EFFORT = "effort"
  const val INITIALIZE = "initialize"
  const val SESSION_NEW = "session/new"
  const val SESSION_SET_CONFIG = "session/set_config_option"
  const val OPEN_CODE_MODEL_CONFIG = "model"
  const val SESSION_UPDATE_METHOD = "session/update"
}

internal object AcpErrorCodes {
  const val AUTHENTICATION_REQUIRED = -32000
}

internal fun modelRpc(id: Int, method: String, params: JsonObject) = buildJsonObject {
  put(ChatModelKeys.JSONRPC, "2.0")
  put(ChatModelKeys.ID, id)
  put(ChatModelKeys.METHOD, method)
  put(ChatModelKeys.PARAMS, params)
}

internal fun parseChatModels(entries: JsonArray, idKey: String, labelKey: String): List<ChatModel>? {
  val models = entries.map { entry ->
    val row = entry as? JsonObject ?: return null
    val id = row.string(idKey)?.takeIf(String::isNotBlank) ?: return null
    ChatModel(id, row.string(labelKey)?.takeIf(String::isNotBlank) ?: id)
  }
  return models.distinctBy { it.id }
}

internal fun acpChatModels(body: JsonObject): List<ChatModel>? {
  val entries = body.obj(ChatModelKeys.MODELS)?.get(ChatModelKeys.AVAILABLE_MODELS) as? JsonArray
  if (entries != null) return parseChatModels(entries, ChatModelKeys.MODEL_ID, ChatModelKeys.NAME)
  val options = (body[ChatModelKeys.CONFIG_OPTIONS] as? JsonArray)?.filterIsInstance<JsonObject>()
    ?.firstOrNull { it.string(ChatModelKeys.CATEGORY) == ChatModelKeys.MODEL }
    ?.get(ChatModelKeys.OPTIONS) as? JsonArray ?: return null
  fun flatten(rows: JsonArray): JsonArray = JsonArray(rows.flatMap { row ->
    val nested = (row as? JsonObject)?.get(ChatModelKeys.OPTIONS) as? JsonArray
    if (nested == null) listOf(row) else flatten(nested).toList()
  })
  return parseChatModels(flatten(options), ChatModelKeys.VALUE, ChatModelKeys.NAME)
}

internal fun cursorChatModels(output: String): List<ChatModel> = output.lineSequence().mapNotNull { line ->
  val clean = line.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "").trim()
  val parts = clean.split(" - ", limit = 2)
  val id = parts.first().trim()
  if (parts.size != 2 || !id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/+\\[\\]=,-]*"))) null
  else ChatModel(id, parts[1].trim())
}.distinctBy { it.id }.toList()
