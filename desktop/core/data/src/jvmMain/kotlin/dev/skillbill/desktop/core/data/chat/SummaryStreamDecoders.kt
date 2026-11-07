package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// One-shot summaries still run `codex exec --json` and Cursor `--print` stream-json; chat turns use app-server and ACP.

/**
 * Decodes `codex exec --json` lines. `error` lines and `error` items (such as config warnings) can be notices the turn
 * recovers from, so only `turn.failed` or an exit without `turn.completed` fails the turn, with the last error as its detail.
 */
internal class CodexStreamDecoder(private val onThread: (String) -> Unit) : ChatLineDecoder {
  private var lastError: String? = null
  private var wroteText = false
  private val started = mutableSetOf<String>()

  override fun decode(line: String): List<ChatEvent> {
    if (line.isBlank()) return emptyList()
    val root = parseStreamLine(line) ?: return listOf(ChatEvent.Failed(ChatFailure.StreamMalformed, line.take(200)))
    return when (root.string("type")) {
      "thread.started" -> {
        root.string("thread_id")?.let(onThread)
        emptyList()
      }
      "item.started" -> root.obj("item")?.let(::toolCall)?.let { call ->
        started += call.id
        listOf(ChatEvent.ToolUse(call))
      }.orEmpty()
      "item.completed" -> root.obj("item")?.let(::completed).orEmpty()
      "turn.completed" -> listOf(ChatEvent.Completed)
      "turn.failed" -> listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, root.obj("error")?.string("message") ?: lastError))
      "error" -> {
        lastError = root.string("message")
        emptyList()
      }
      else -> emptyList()
    }
  }

  override fun exited(code: Int, stderrTail: String): List<ChatEvent> =
    listOf(exitFailure(code, lastError ?: stderrTail))

  private fun completed(item: JsonObject): List<ChatEvent> {
    if (item.string("type") == "error") {
      lastError = item.string("message") ?: lastError
      return emptyList()
    }
    if (item.string("type") == "agent_message") {
      val text = item.string("text")?.takeIf { it.isNotBlank() } ?: return emptyList()
      val separated = if (wroteText) "\n\n$text" else text
      wroteText = true
      return listOf(ChatEvent.TextDelta(separated))
    }
    val call = toolCall(item) ?: return emptyList()
    return buildList {
      if (call.id !in started) add(ChatEvent.ToolUse(call))
      add(ChatEvent.ToolResult(call.id, toolOutput(item), toolFailed(item)))
    }
  }

  private fun toolCall(item: JsonObject): ChatToolCall? {
    val id = item.string("id") ?: return null
    return when (val type = item.string("type")) {
      null, "agent_message", "reasoning", "todo_list", "error" -> null
      "command_execution" -> item.string("command").orEmpty().let { ChatToolCall(id, "shell", it, it) }
      "file_change" -> {
        val changes = (item["changes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val lines = changes.map { "${it.string("kind") ?: "update"} ${it.string("path").orEmpty()}" }
        ChatToolCall(id, "edit", lines.joinToString("\n"), changes.firstOrNull()?.string("path"))
      }
      "mcp_tool_call" -> ChatToolCall(
        id,
        "mcp__${item.string("server")}__${item.string("tool")}",
        item["arguments"]?.renderToolContent().orEmpty(),
        item.string("tool"),
      )
      "web_search" -> ChatToolCall(id, "websearch", item.string("query").orEmpty(), item.string("query"))
      else -> ChatToolCall(id, type, item.renderToolContent(), null)
    }
  }

  private fun toolOutput(item: JsonObject): String =
    item.string("aggregated_output")
      ?: item.obj("error")?.string("message")
      ?: item["result"]?.renderToolContent()
      ?: item.string("status").orEmpty()

  private fun toolFailed(item: JsonObject): Boolean =
    item.string("status") == "failed" || (item.int("exit_code") ?: 0) != 0 || item.obj("error") != null
}

/** Decodes Cursor stream-json lines; without partial output each `assistant` line is one whole message. */
internal class CursorStreamDecoder(private val onChat: (String) -> Unit) : ChatLineDecoder {
  private var reported = false
  private var wroteText = false

  override fun decode(line: String): List<ChatEvent> {
    if (line.isBlank()) return emptyList()
    val root = parseStreamLine(line) ?: return listOf(ChatEvent.Failed(ChatFailure.StreamMalformed, line.take(200)))
    if (!reported) {
      root.string("session_id")?.let {
        reported = true
        onChat(it)
      }
    }
    return when (root.string("type")) {
      "assistant" -> assistantText(root)?.let { text ->
        val separated = if (wroteText) "\n\n$text" else text
        wroteText = true
        listOf(ChatEvent.TextDelta(separated))
      }.orEmpty()
      "tool_call" -> toolEvent(root)
      "result" -> if ((root["is_error"] as? JsonPrimitive)?.booleanOrNull == true) {
        listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, root.string("result") ?: root.string("subtype")))
      } else {
        listOf(ChatEvent.Completed)
      }
      "error" -> listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, root.string("message") ?: root.string("error_type")))
      else -> emptyList()
    }
  }

  private fun assistantText(root: JsonObject): String? {
    val message = root.obj("message")
    val content = message?.get("content") as? JsonArray
    val text = content?.mapNotNull { (it as? JsonObject)?.string("text") }?.joinToString("") ?: message?.string("text")
    return text?.takeIf { it.isNotBlank() }
  }

  /** `tool_call` holds one `<name>ToolCall` object with `args`, and `result` once completed. */
  private fun toolEvent(root: JsonObject): List<ChatEvent> {
    val id = root.string("call_id") ?: return emptyList()
    val entry = root.obj("tool_call")?.entries?.firstOrNull() ?: return emptyList()
    val body = entry.value as? JsonObject ?: return emptyList()
    val name = entry.key.removeSuffix("ToolCall")
    val args = body.obj("args")
    val summary = args?.let { it.string("command") ?: it.string("path") ?: it.string("pattern") ?: it.string("query") }
    val call = ChatToolCall(id, name, args?.renderToolContent().orEmpty(), summary)
    return when (root.string("subtype")) {
      "started" -> listOf(ChatEvent.ToolUse(call))
      "completed" -> {
        val result = body.obj("result")
        val failure = result?.obj("error") ?: result?.obj("failure")
        val output = (failure ?: result?.obj("success") ?: result)?.renderToolContent().orEmpty()
        listOf(ChatEvent.ToolUse(call), ChatEvent.ToolResult(id, output, failure != null))
      }
      else -> emptyList()
    }
  }
}
