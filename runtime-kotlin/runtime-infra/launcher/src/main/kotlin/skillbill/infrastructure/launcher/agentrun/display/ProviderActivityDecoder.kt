package skillbill.infrastructure.launcher.agentrun.display

import com.fasterxml.jackson.databind.JsonNode
import skillbill.ports.agentrun.activity.model.AgentRunActivity
import skillbill.ports.agentrun.activity.model.AgentRunActivityKind
import skillbill.ports.agentrun.activity.model.AgentRunActivityState
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.util.UUID

internal class ProviderActivityDecoder(private val agent: String, private val diagnostics: RuntimeDiagnostics) {
  private val launchId = UUID.randomUUID().toString()
  private val calls = linkedMapOf<String, AgentRunActivity>()
  private var streamSeen = false
  private var overflowReported = false

  fun decode(event: JsonNode): List<AgentRunActivity> {
    if (agent !in SUPPORTED || event.path("type").asText().isBlank()) return emptyList()
    return buildList {
      if (!streamSeen) {
        streamSeen = true
        add(AgentRunActivity("$launchId:stream", AgentRunActivityKind.STREAM, "", agent))
      }
      when (agent) {
        "claude" -> addAll(claude(event))
        "codex" -> addAll(codex(event))
        "cursor" -> addAll(cursor(event))
      }
    }
  }

  private fun claude(event: JsonNode): List<AgentRunActivity> =
    buildList {
      when (event.path("type").asText()) {
        "assistant" ->
          event.path("message").path("content").forEach { block ->
            when (block.path("type").asText()) {
              "text" -> note(block.path("text").asText())?.let(::add)
              "tool_use" -> claudeTool(block)?.let(::add)
            }
          }
        "content_block_start" -> {
          val block = event.path("content_block")
          if (block.path("type").asText() == "tool_use") {
            claudeTool(block)?.let(::add)
          }
        }
        "user" ->
          event.path("message").path("content").forEach { block ->
            if (block.path("type").asText() == "tool_result") {
              claudeResult(block)?.let(::add)
            }
          }
        "error" -> error(event)?.let(::add)
      }
    }

  private fun codex(event: JsonNode): List<AgentRunActivity> =
    buildList {
      val type = event.path("type").asText()
      val item = event.path("item")
      when {
        type == "agent_message" -> note(event.path("message").asText(event.path("text").asText()))?.let(::add)
        type in ITEM_EVENTS -> {
          val kind = item.path("type").asText()
          if (kind == "agent_message" && type == "item.completed") {
            note(item.path("text").asText())?.let(::add)
          } else if (kind in CODEX_TOOLS) {
            addAll(codexTools(type, item))
          }
        }
        type == "event_msg" && event.path("payload").path("type").asText() == "agent_message" ->
          note(event.path("payload").path("message").asText())?.let(::add)
      }
    }

  private fun codexTools(
    type: String,
    item: JsonNode,
  ): List<AgentRunActivity> =
    buildList {
      val id = item.path("id").asText()
      val kind = item.path("type").asText()
      val name =
        when (kind) {
          "command_execution" -> "Bash"
          "file_change" -> "Write"
          "web_search" -> "Search"
          "mcp_tool_call" -> "mcp__${item.path("server").asText()}__${item.path("tool").asText()}"
          else -> item.path("name").asText(kind)
        }
      val inputs =
        when (kind) {
          "file_change" -> item.path("changes").map { it.path("path") }.ifEmpty { listOf(item.path("path")) }
          "command_execution" -> listOf(item.path("command"))
          "web_search" -> listOf(item.path("query"))
          else -> listOf(item.path("arguments").takeUnless { it.isMissingNode } ?: item.path("input"))
        }
      inputs.forEach { input ->
        val callId = if (kind == "file_change") "$id:${input.asText()}" else id
        tool(callId, name, input)?.let(::add)
        if (type == "item.completed") codexCompletion(callId, item)?.let(::add)
      }
    }

  private fun codexCompletion(
    id: String,
    item: JsonNode,
  ): AgentRunActivity? {
    val exit = item.path("exit_code").takeIf { it.isIntegralNumber }?.asInt()
    val failed = item.path("status").asText() == "failed" || exit?.let { it != 0 } == true
    val duration = item.path("duration_ms").takeIf { it.isIntegralNumber }?.asLong()
    return complete(id, failed, exit, duration)
  }

  private fun claudeResult(block: JsonNode): AgentRunActivity? =
    complete(block.path("tool_use_id").asText(), block.path("is_error").asBoolean(false), null, null)

  private fun error(event: JsonNode): AgentRunActivity? {
    val text = event.path("message").asText(event.path("error").asText())
    return note(text)?.copy(kind = AgentRunActivityKind.ERROR, state = AgentRunActivityState.FAILED)
  }

  private fun claudeTool(block: JsonNode): AgentRunActivity? =
    tool(block.path("id").asText(), block.path("name").asText(), block.path("input"))

  private fun cursor(event: JsonNode): List<AgentRunActivity> {
    if (event.path("type").asText() != "tool_call") return claude(event)
    val id = event.path("call_id").asText()
    val entry = event.path("tool_call").fields().asSequence().firstOrNull() ?: return emptyList()
    return buildList {
      tool(id, entry.key.removeSuffix("ToolCall"), entry.value.path("args"))?.let(::add)
      if (event.path("subtype").asText() == "completed") {
        complete(id, entry.value.path("error").isObject, null, null)?.let(::add)
      }
    }
  }

  private fun note(text: String): AgentRunActivity? =
    text.takeIf(String::isNotBlank)?.let {
      AgentRunActivity("$launchId:${UUID.randomUUID()}", AgentRunActivityKind.NOTE, it.take(MAX_TEXT), agent)
    }

  private fun tool(
    id: String,
    name: String,
    input: JsonNode,
  ): AgentRunActivity? {
    if (!canTrack(id, name, input)) return null
    val kind = toolKind(name)
    val path = toolPath(kind, input)
    val text = toolText(kind, name, input, path)
    return AgentRunActivity(
      "$launchId:$id",
      kind,
      text.take(MAX_TEXT),
      agent,
      path?.takeIf(String::isNotBlank),
      AgentRunActivityState.STARTED,
    ).also {
      calls[id] = it
    }
  }

  private fun canTrack(
    id: String,
    name: String,
    input: JsonNode,
  ): Boolean {
    if (id.isBlank() || name.isBlank() || id in calls) return false
    if (input.isMissingNode || input.isNull) return false
    if (input.toString() == "{}" && !name.startsWith("mcp__")) return false
    if (calls.size >= MAX_CALLS && !overflowReported) {
      diagnostics.warning("seam=plan_activity_decode value_expected=bounded_calls value_used=omitted")
      overflowReported = true
    }
    return calls.size < MAX_CALLS
  }

  private fun toolKind(name: String): AgentRunActivityKind {
    val normalized = name.lowercase()
    return when {
      normalized.startsWith("mcp__") -> AgentRunActivityKind.MCP
      normalized in READ_TOOLS -> AgentRunActivityKind.READ
      normalized in WRITE_TOOLS -> AgentRunActivityKind.WRITE
      normalized in SEARCH_TOOLS -> AgentRunActivityKind.SEARCH
      normalized in COMMAND_TOOLS -> AgentRunActivityKind.COMMAND
      else -> AgentRunActivityKind.TOOL
    }
  }

  private fun toolPath(
    kind: AgentRunActivityKind,
    input: JsonNode,
  ): String? {
    return if (kind == AgentRunActivityKind.READ || kind == AgentRunActivityKind.WRITE) {
      if (input.isTextual) input.asText() else input.path("file_path").asText(input.path("path").asText())
    } else {
      null
    }
  }

  private fun toolText(
    kind: AgentRunActivityKind,
    name: String,
    input: JsonNode,
    path: String?,
  ): String {
    return when (kind) {
      AgentRunActivityKind.READ, AgentRunActivityKind.WRITE -> path.orEmpty()
      AgentRunActivityKind.COMMAND -> inputText(input, "command")
      AgentRunActivityKind.SEARCH -> inputText(input, "pattern")
      AgentRunActivityKind.MCP -> name.removePrefix("mcp__")
      else -> "$name $input"
    }
  }

  private fun inputText(
    input: JsonNode,
    field: String,
  ): String = if (input.isTextual) input.asText() else input.path(field).asText(input.toString())

  private fun complete(
    id: String,
    failed: Boolean,
    exit: Int?,
    duration: Long?,
  ): AgentRunActivity? {
    val call = calls[id] ?: return null
    if (call.state != AgentRunActivityState.STARTED) return null
    return call.copy(
      state = if (failed) AgentRunActivityState.FAILED else AgentRunActivityState.COMPLETED,
      exitCode = exit,
      durationMs = duration,
    ).also {
      calls[id] = it
    }
  }

  private companion object {
    const val MAX_TEXT = 8192
    const val MAX_CALLS = 512
    val SUPPORTED = setOf("claude", "codex", "cursor")
    val ITEM_EVENTS = setOf("item.started", "item.updated", "item.completed")
    val CODEX_TOOLS =
      setOf("command_execution", "file_change", "mcp_tool_call", "web_search", "tool_call", "function_call")
    val READ_TOOLS = setOf(AgentRunActivityKind.READ.wireValue, "readfile", "read_file")
    val WRITE_TOOLS =
      setOf(
        AgentRunActivityKind.WRITE.wireValue,
        "edit",
        "multiedit",
        "editfile",
        "writefile",
        "apply_patch",
        "file_change",
      )
    val SEARCH_TOOLS = setOf("grep", "glob", AgentRunActivityKind.SEARCH.wireValue, "websearch", "web_search")
    val COMMAND_TOOLS =
      setOf(
        "bash",
        AgentRunActivityKind.COMMAND.wireValue,
        "command_execution",
        "shell",
        "exec_command",
        "runterminalcommand",
      )
  }
}
