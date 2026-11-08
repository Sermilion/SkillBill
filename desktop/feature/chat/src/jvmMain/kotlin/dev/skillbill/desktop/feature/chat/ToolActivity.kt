package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatToolCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val ACTIVITY_LIMIT = 88

internal fun toolActivityLabel(call: ChatToolCall): String {
  val name = call.name.substringAfterLast("__").substringAfterLast('.')
  val args = toolArguments(call.input)
  fun argument(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (args?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
  }
  fun file(): String? = (argument("file_path", "path", "file")
    ?: call.input.takeIf { args == null && it.isNotBlank() && it.lineSequence().count() == 1 && !it.startsWith("{") })
    ?.replace('\\', '/')?.substringAfterLast('/')?.takeIf(::isActivityFileName)
  fun label(verb: String, detail: String?, fallback: String): String =
    if (detail.isNullOrBlank() || !isActivityDetail(detail)) fallback else "$verb $detail"
  val label = when (name.lowercase()) {
    "edit", "multiedit", "write", "notebookedit", "str_replace", "delete", "file_change" ->
      label("Editing", file(), "Editing files...")
    "apply_patch" -> {
      val patch = argument("patch", "input") ?: call.input
      val path = patch.lineSequence().firstNotNullOfOrNull { line ->
        listOf("*** Update File: ", "*** Add File: ", "*** Delete File: ")
          .firstOrNull { line.startsWith(it) }?.let { line.removePrefix(it) }
      }?.replace('\\', '/')?.substringAfterLast('/')?.takeIf(::isActivityFileName)
      label("Editing", path, "Editing files...")
    }
    "read", "read_file", "notebookread" -> label("Reading", file(), "Reading files...")
    "grep", "glob", "search", "webfetch", "websearch", "web_search" -> {
      val query = argument("pattern", "query", "url")
        ?: call.input.takeIf { args == null && name.lowercase() in setOf("websearch", "web_search") }
      label("Searching for", query, "Searching...")
    }
    "ls" -> label("Listing", file(), "Listing files...")
    "bash", "shell", "exec", "exec_command", "run", "command_execution" -> "Running command..."
    "wait", "wait_agent", "sleep", "write_stdin" -> "Waiting for response..."
    "task", "agent", "spawn_agent" -> label("Agent working on", argument("description", "task_name"), "Agent working...")
    else -> {
      val server = call.name.takeIf { it.startsWith("mcp__") }?.removePrefix("mcp__")?.substringBefore("__")
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,48}")) }
      if (server != null) "Calling $server MCP" else "Using tools..."
    }
  }
  val compact = label.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
  return if (compact.length > ACTIVITY_LIMIT) compact.take(ACTIVITY_LIMIT - 3) + "..." else compact
}

private fun toolArguments(input: String): JsonObject? {
  if (!input.trimStart().startsWith("{")) return null
  return try {
    Json.parseToJsonElement(input) as? JsonObject
  } catch (_: SerializationException) {
    null
  }
}

private fun isActivityFileName(value: String): Boolean = value.matches(Regex("[\\p{L}\\p{N}_.-]+"))

private fun isActivityDetail(value: String): Boolean =
  value.none { it.isISOControl() || it in "`$;|&<>{}" }
