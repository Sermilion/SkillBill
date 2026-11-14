package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.skillbill.desktop.core.data.chat.ChatModelKeys as K

/**
 * One ACP turn on `opencode acp` that returns the agent's reply text, or null on any protocol failure.
 * `opencode run` ends its stdout before the reply arrives, so label generation goes through the same
 * ACP vocabulary the chat transport uses, without session or workspace persistence.
 */
internal suspend fun openCodeLabelReply(
  launcher: BoundedProtocolLauncher,
  command: ChatCommand,
  model: String,
  prompt: String,
  diagnostics: Diagnostics,
): String? {
  val started = try {
    launcher.start(command)
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    diagnostics.record("chat.label_acp_start_failed")
    return null
  }
  val connection = (started as? BoundedProcessStart.Started)?.connection ?: return null
  return try {
    openCodeReply(connection, command.workingDirectory, model, prompt)
  } finally {
    withContext(NonCancellable) { connection.close() }
  }
}

private suspend fun openCodeReply(
  connection: BoundedProtocolConnection,
  cwd: String,
  model: String,
  prompt: String,
): String? {
  val text = StringBuilder()
  var nextId = 0

  suspend fun call(method: String, params: JsonObject): JsonObject? {
    val id = ++nextId
    connection.beginResponseBudget()
    try {
      if (!connection.sendLine(modelRpc(id, method, params).toString())) return null
      while (true) {
        val line = (connection.receive() as? BoundedProtocolInput.Line)?.text ?: return null
        val message = parseStreamLine(line) ?: return null
        when {
          message[K.ID] == JsonPrimitive(id) && K.METHOD !in message ->
            return if (K.ERROR in message) null else message.obj(K.RESULT) ?: JsonObject(emptyMap())
          K.METHOD in message && K.ID in message -> answerRequest(connection, message)
          message.string(K.METHOD) == K.SESSION_UPDATE_METHOD -> collectUpdate(message.obj(K.PARAMS), text)
        }
      }
    } finally {
      connection.endResponseBudget()
    }
  }

  call(K.INITIALIZE, buildJsonObject {
    put(K.PROTOCOL_VERSION, 1)
    put(K.CLIENT_CAPABILITIES, JsonObject(emptyMap()))
    put(K.CLIENT_INFO, buildJsonObject {
      put(K.NAME, "skill-bill")
      put(K.VERSION, "1.0.0")
    })
  }) ?: return null
  val setup = call(K.SESSION_NEW, buildJsonObject {
    put(K.CWD, cwd)
    put(K.MCP_SERVERS, JsonArray(emptyList()))
  }) ?: return null
  val sessionId = setup.string(K.SESSION_ID)?.takeIf(String::isNotBlank) ?: return null
  call(K.SESSION_SET_CONFIG, buildJsonObject {
    put(K.SESSION_ID, sessionId)
    put(K.CONFIG_ID, K.OPEN_CODE_MODEL_CONFIG)
    put(K.VALUE, model)
  }) ?: return null
  val result = call(K.SESSION_PROMPT, buildJsonObject {
    put(K.SESSION_ID, sessionId)
    put(K.PROMPT, buildJsonArray {
      add(buildJsonObject {
        put(K.TYPE, K.TEXT)
        put(K.TEXT, prompt)
      })
    })
  }) ?: return null
  return if (result.string(K.STOP_REASON) == K.END_TURN) text.toString() else null
}

/** The agent's own requests get an allow-once choice when offered, else a cancel, so the turn never stalls. */
private suspend fun answerRequest(connection: BoundedProtocolConnection, message: JsonObject) {
  val requestId = message[K.ID] ?: return
  if (message.string(K.METHOD) != K.SESSION_REQUEST_PERMISSION) {
    connection.sendLine(buildJsonObject {
      put(K.JSONRPC, "2.0")
      put(K.ID, requestId)
      put(K.ERROR, buildJsonObject {
        put(K.ERROR_CODE, AcpErrorCodes.METHOD_NOT_FOUND)
        put(K.ERROR_MESSAGE, "label turn answers permission requests only")
      })
    }.toString())
    return
  }
  val allow = (message.obj(K.PARAMS)?.get(K.OPTIONS) as? JsonArray)
    ?.filterIsInstance<JsonObject>()
    ?.firstOrNull { it.string(K.KIND) == K.ALLOW_ONCE }
    ?.string(K.OPTION_ID)
  connection.sendLine(buildJsonObject {
    put(K.JSONRPC, "2.0")
    put(K.ID, requestId)
    put(K.RESULT, buildJsonObject {
      put(K.OUTCOME, buildJsonObject {
        put(K.OUTCOME, if (allow == null) K.CANCELLED else K.SELECTED)
        allow?.let { put(K.OPTION_ID, it) }
      })
    })
  }.toString())
}

private fun collectUpdate(params: JsonObject?, text: StringBuilder) {
  val update = params?.obj(K.UPDATE) ?: return
  if (update.string(K.SESSION_UPDATE) != K.AGENT_MESSAGE_CHUNK) return
  update.obj(K.CONTENT)?.string(K.TEXT)?.let(text::append)
}
