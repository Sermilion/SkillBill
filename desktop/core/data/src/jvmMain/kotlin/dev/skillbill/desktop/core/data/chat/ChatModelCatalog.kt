package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.usage.UsageProfileResolver
import dev.skillbill.desktop.core.data.usage.codexEnvironment
import dev.skillbill.desktop.core.data.usage.cursorEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.tatarka.inject.annotations.Inject

@Inject
class ChatModelCatalog(
  private val host: HostEnvironment,
  private val launcher: BoundedProtocolLauncher,
  private val processes: ChatProcessLauncher,
  private val profiles: UsageProfileResolver,
  private val settings: AppSettingsRepository,
  private val diagnostics: Diagnostics,
) {
  suspend fun read(agent: ChatAgent, repositoryRoot: String): ChatModelsRead = withContext(Dispatchers.IO) {
    val result = try {
      withTimeoutOrNull(20_000) { discover(agent, repositoryRoot) }
        ?: ChatModelsRead.Failed("Model discovery timed out. Check the agent's connection and retry.")
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      ChatModelsRead.Failed("Could not read models. Check the agent installation and sign-in, then retry.")
    }
    if (result is ChatModelsRead.Failed) diagnostics.record("chat.model_discovery_failed")
    result
  }

  private suspend fun discover(agent: ChatAgent, root: String): ChatModelsRead {
    val environment = when (agent.agentId) {
      "claude" -> profiles.claude(agent.profile ?: settings.settings.first().claudeConfigDir).environment
      "codex" -> codexEnvironment(host.variables, agent.profile)
      "cursor" -> cursorEnvironment(host.variables, agent.profile)
      "junie" -> host.variables
      else -> return ChatModelsRead.Failed("This agent does not expose a model list.")
    }
    val arguments = when (agent.agentId) {
      "claude" -> listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--no-session-persistence")
      "codex" -> listOf("app-server")
      "cursor" -> listOf("--list-models")
      else -> listOf("--acp")
    }
    val binaries = if (agent.agentId == "cursor") listOf("cursor-agent", "agent") else listOf(agent.agentId)
    val command = ChatCommand(agentCommand(binaries, arguments, host.variables["SHELL"]), root, environment, "")
    if (agent.agentId == "cursor") return cursor(command)
    val started = launcher.start(command)
    if (started !is BoundedProcessStart.Started) return unavailable()
    val connection = started.connection
    return try {
      when (agent.agentId) {
        "claude" -> claude(connection)
        "codex" -> codex(connection)
        else -> junie(connection, root)
      }
    } finally {
      withContext(NonCancellable) { connection.close() }
    }
  }

  private suspend fun cursor(command: ChatCommand): ChatModelsRead {
    val output = StringBuilder()
    var exit: Int? = null
    processes.run(command).collect { event ->
      when (event) {
        is ChatProcessOutput.Line -> if (output.length < 1_000_000) output.appendLine(event.text)
        is ChatProcessOutput.Exited -> exit = event.code
        is ChatProcessOutput.StartFailed -> exit = -1
        is ChatProcessOutput.StdoutEnded -> Unit
      }
    }
    if (exit != 0 || output.length >= 1_000_000) return unavailable()
    val models = cursorChatModels(output.toString())
    return if (models.isEmpty()) unavailable() else ChatModelsRead.Loaded(models)
  }

  private suspend fun claude(connection: BoundedProtocolConnection): ChatModelsRead {
    val id = "chat-models"
    val frame = buildJsonObject {
      put(ChatModelKeys.TYPE, "control_request")
      put(ChatModelKeys.REQUEST_ID, id)
      put(ChatModelKeys.REQUEST, buildJsonObject { put(ChatModelKeys.SUBTYPE, "initialize") })
    }
    if (!connection.sendLine(frame.toString())) return unavailable()
    val response = receive(connection) {
      it.string(ChatModelKeys.TYPE) == "control_response" && it.obj(ChatModelKeys.RESPONSE)?.string(ChatModelKeys.REQUEST_ID) == id
    }?.obj(ChatModelKeys.RESPONSE) ?: return unavailable()
    if (response.string(ChatModelKeys.SUBTYPE) != "success") return unavailable()
    val rows = response.obj(ChatModelKeys.RESPONSE)?.get(ChatModelKeys.MODELS) as? JsonArray ?: return unavailable()
    return parseChatModels(rows, ChatModelKeys.VALUE, ChatModelKeys.DISPLAY_NAME)?.let(ChatModelsRead::Loaded) ?: unavailable()
  }

  private suspend fun codex(connection: BoundedProtocolConnection): ChatModelsRead {
    val info = buildJsonObject {
      put(ChatModelKeys.CLIENT_INFO, buildJsonObject {
        put(ChatModelKeys.NAME, "skill-bill-models")
        put(ChatModelKeys.VERSION, "1")
      })
    }
    if (call(connection, 1, "initialize", info) == null) return unavailable()
    if (!connection.sendLine(buildJsonObject { put(ChatModelKeys.METHOD, "initialized") }.toString())) return unavailable()
    val models = mutableListOf<ChatModel>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    var id = 2
    do {
      val params = buildJsonObject {
        put(ChatModelKeys.LIMIT, 100)
        cursor?.let { put(ChatModelKeys.CURSOR, it) }
      }
      val body = call(connection, id++, "model/list", params) ?: return unavailable()
      val rows = body[ChatModelKeys.DATA] as? JsonArray ?: return unavailable()
      models += parseChatModels(rows, ChatModelKeys.MODEL, ChatModelKeys.DISPLAY_NAME) ?: return unavailable()
      val next = body[ChatModelKeys.NEXT_CURSOR]
      if (next != null && next != JsonNull && (next !is JsonPrimitive || !next.isString)) return unavailable()
      cursor = body.string(ChatModelKeys.NEXT_CURSOR)
      if (cursor != null && (!cursors.add(cursor) || cursors.size > 100)) return unavailable()
    } while (cursor != null)
    return ChatModelsRead.Loaded(models.distinctBy { it.id })
  }

  private suspend fun junie(connection: BoundedProtocolConnection, root: String): ChatModelsRead {
    val params = buildJsonObject {
      put(ChatModelKeys.PROTOCOL_VERSION, 1)
      put(ChatModelKeys.CLIENT_CAPABILITIES, JsonObject(emptyMap()))
    }
    if (call(connection, 1, "initialize", params) == null) return unavailable()
    val session = call(connection, 2, "session/new", buildJsonObject {
      put(ChatModelKeys.CWD, root)
      put(ChatModelKeys.MCP_SERVERS, JsonArray(emptyList()))
    }) ?: return unavailable()
    return acpChatModels(session)?.let(ChatModelsRead::Loaded) ?: unavailable()
  }

  private suspend fun call(connection: BoundedProtocolConnection, id: Int, method: String, params: JsonObject): JsonObject? {
    if (!connection.sendLine(modelRpc(id, method, params).toString())) return null
    val response = receive(connection) { it[ChatModelKeys.ID] == JsonPrimitive(id) } ?: return null
    if (ChatModelKeys.ERROR in response) return null
    return response.obj(ChatModelKeys.RESULT)
  }

  private suspend fun receive(connection: BoundedProtocolConnection, matches: (JsonObject) -> Boolean): JsonObject? {
    while (true) {
      when (val input = connection.receive()) {
        is BoundedProtocolInput.Line -> {
          val body = parseStreamLine(input.text) ?: return null
          if (matches(body)) return body
        }
        else -> return null
      }
    }
  }

  private fun unavailable() = ChatModelsRead.Failed("Model list unavailable. Check this agent's installation and sign-in, then retry.")
}
