package skillbill.infrastructure.launcher.agentrun.display

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.infrastructure.launcher.process.launch.AgentRunOutputEndObserver
import skillbill.ports.agentrun.model.AgentRunActivityOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal class ReadableAgentOutputSink(
  private val agent: String,
  private val delegate: AgentRunOutputSink,
  private val diagnostics: RuntimeDiagnostics,
) : AgentRunOutputSink, AgentRunOutputEndObserver {
  private val pending = mutableMapOf<AgentRunOutputStream, StringBuilder>()
  private val dropping = mutableSetOf<AgentRunOutputStream>()
  private val emittedCodexCalls = linkedSetOf<String>()
  private val emittedCursorCalls = linkedSetOf<String>()
  private val cursorPartialLine = StringBuilder()
  private val cursorPartialMessage = StringBuilder()
  private var droppingCursorPartialLine = false
  private var cursorPartialMessageOverflowed = false
  private var cursorPartialTextPending = false
  private var toolTrackingOverflowReported = false
  private val mapper = ObjectMapper()
  private val activitySink = (delegate as? AgentRunActivityOutputSink)?.takeIf { it.capturesActivity }
  private var activityDecoder = activitySink?.let { ProviderActivityDecoder(agent, diagnostics) }

  @Synchronized
  override fun write(
    stream: AgentRunOutputStream,
    text: String,
  ) {
    if (text == STREAM_END) {
      flushStream(stream)
      return
    }
    if (stream == AgentRunOutputStream.STDERR) {
      val buffer = pending.getOrPut(stream) { StringBuilder() }
      text.forEach { char ->
        if (char == '\n') {
          activitySink?.raw(stream, buffer.toString().trimEnd('\r'))
          delegate.write(stream, "${buffer.toString().trimEnd('\r')}\n")
          buffer.setLength(0)
        } else {
          buffer.append(char)
          if (buffer.length == MAX_LINE_CHARS) {
            delegate.write(stream, "$buffer\n")
            buffer.setLength(0)
          }
        }
      }
      return
    }
    val buffer = pending.getOrPut(stream) { StringBuilder() }
    text.forEach { char ->
      if (dropping.contains(stream)) {
        if (char == '\n') dropping.remove(stream)
      } else if (char == '\n') {
        emit(stream, buffer.toString().trimEnd('\r'))
        buffer.setLength(0)
      } else if (buffer.length < MAX_EVENT_CHARS) {
        buffer.append(char)
      } else {
        buffer.setLength(0)
        dropping.add(stream)
        diagnostics.warning("seam=standalone_output_decode value_expected=bounded_event value_used=truncated")
      }
    }
  }

  @Synchronized
  fun finish() {
    pending.keys.toList().forEach(::flushStream)
  }

  @Synchronized
  override fun outputEnded(stream: AgentRunOutputStream) {
    if (stream == AgentRunOutputStream.STDOUT) {
      flushStream(stream)
      activityDecoder = activitySink?.let { ProviderActivityDecoder(agent, diagnostics) }
      emittedCodexCalls.clear()
      toolTrackingOverflowReported = false
    }
    (delegate as? AgentRunOutputEndObserver)?.outputEnded(stream)
  }

  private fun flushStream(stream: AgentRunOutputStream) {
    val buffer = pending[stream]
    if (buffer != null) {
      if (buffer.isNotEmpty() && !dropping.contains(stream)) {
        if (stream == AgentRunOutputStream.STDERR) {
          activitySink?.raw(stream, buffer.toString())
          delegate.write(stream, "${buffer}\n")
        } else {
          emit(stream, buffer.toString())
        }
      }
      buffer.setLength(0)
      dropping.remove(stream)
    }
    if (stream == AgentRunOutputStream.STDOUT && agent == "cursor") flushCursorPartial(stream)
  }

  private fun emit(
    stream: AgentRunOutputStream,
    line: String,
  ) {
    if (line.isBlank()) return
    activitySink?.raw(stream, line)
    val trimmed = line.take(MAX_LINE_CHARS)
    if (agent == "junie") {
      delegate.write(stream, "$trimmed\n")
      return
    }
    val event =
      try {
        mapper.readTree(line)
      } catch (_: JsonProcessingException) {
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
          diagnostics.warning("seam=standalone_output_decode value_expected=provider_event value_used=malformed")
          return
        }
        delegate.write(stream, "$trimmed\n")
        return
      }
    publishActivity(event)
    val type = event.path("type").asText("")
    if (agent == "cursor" && type != "partial" && type != "assistant") {
      flushCursorPartial(stream)
    }
    when {
      type in OMITTED_EVENTS -> Unit
      agent == "claude" -> emitClaude(stream, event)
      agent == "cursor" -> emitCursor(stream, event)
      agent == "codex" -> emitCodex(stream, event)
      else -> {
        diagnostics.warning("seam=standalone_output_decode value_expected=plain_text value_used=provider_event")
        delegate.write(stream, "[provider event: ${type.take(MAX_EVENT_TYPE_CHARS)}]\n")
      }
    }
  }

  private fun publishActivity(event: JsonNode) {
    activityDecoder?.decode(event)?.forEach { activitySink?.activity(it) }
  }

  private fun emitClaude(
    stream: AgentRunOutputStream,
    event: JsonNode,
  ) {
    when (event.path("type").asText()) {
      "content_block_delta" -> {
        text(stream, event.path("delta").path("text").asText(""))
        return
      }
      "content_block_start" -> {
        val block = event.path("content_block")
        if (block.path("type").asText() == "tool_use") {
          tool(stream, block.path("name").asText("tool"), block.path("input"))
        }
        return
      }
    }
    val content = event.path("message").path("content")
    if (event.path("type").asText() != "assistant" || !content.isArray) return fallback(event)
    content.forEach { item ->
      when (item.path("type").asText()) {
        "text" -> text(stream, item.path("text").asText(""))
        "tool_use" -> tool(stream, item.path("name").asText("tool"), item.path("input"))
      }
    }
  }

  private fun emitCursor(
    stream: AgentRunOutputStream,
    event: JsonNode,
  ) {
    val type = event.path("type").asText()
    when (type) {
      "partial" -> cursorPartial(stream, event.path("delta").asText(""))
      "assistant" -> {
        val message = event.path("message")
        val content = message.path("content")
        if (content.isArray) {
          val wholeText =
            content.mapNotNull { item ->
              item.takeIf { it.path("type").asText() == "text" }?.path("text")?.asText()
            }.joinToString("")
          emitCursorWholeText(stream, wholeText)
          content.forEach { item ->
            if (item.path("type").asText() in TOOL_EVENTS) {
              tool(stream, item.path("name").asText("tool"), item.path("input"))
            }
          }
        } else {
          val wholeText = content.asText(message.path("text").asText(""))
          emitCursorWholeText(stream, wholeText)
          event.path("tool_calls").forEach { tool(stream, it.path("name").asText("tool"), it.path("input")) }
        }
      }
      "content_block_delta" -> text(stream, event.path("delta").path("text").asText(""))
      "content_block_start" -> {
        val block = event.path("content_block")
        if (block.path("type").asText() == "tool_use") {
          tool(stream, block.path("name").asText("tool"), block.path("input"))
        }
      }
      "tool_call" -> emitCursorToolCall(stream, event)
      in TOOL_EVENTS -> tool(stream, event.path("name").asText("tool"), event.path("input"))
      else -> fallback(event)
    }
  }

  private fun emitCursorWholeText(
    stream: AgentRunOutputStream,
    wholeText: String,
  ) {
    if (cursorPartialMessageOverflowed) {
      flushCursorPartial(stream)
      return
    }
    val overlap =
      if (cursorPartialMessage.isNotEmpty()) {
        val partial = cursorPartialMessage.toString()
        val lineContent = partial.trimEnd('\n', '\r')
        when {
          wholeText.startsWith(partial) -> partial.length
          partial.endsWith("\r\n") && wholeText.startsWith("$lineContent\r\n") -> lineContent.length + 2
          partial.endsWith("\n") && wholeText.startsWith("$lineContent\n") -> lineContent.length + 1
          wholeText.startsWith(lineContent) -> lineContent.length
          else -> 0
        }
      } else {
        0
      }
    if (cursorPartialMessage.isNotEmpty() && overlap == 0) flushCursorPartial(stream)
    if (overlap > 0) {
      cursorPartial(stream, wholeText.drop(overlap))
    } else {
      text(stream, wholeText)
    }
    flushCursorPartial(stream)
  }

  private fun emitCursorToolCall(
    stream: AgentRunOutputStream,
    event: JsonNode,
  ) {
    val subtype = event.path("subtype").asText("")
    if (subtype != "started" && subtype != "completed") {
      fallback(event)
      return
    }
    val id = event.path("call_id").asText("")
    val entry = event.path("tool_call").fields().asSequence().firstOrNull()
    val body = entry?.value
    val args = body?.path("args")
    if (id.isBlank() || entry == null || !hasUsefulInput(args)) {
      diagnostics.warning("seam=standalone_output_decode value_expected=cursor_tool_input value_used=omitted")
      return
    }
    val toolArgs = args ?: return
    if (!rememberCall(emittedCursorCalls, id)) return
    val name = entry.key.removeSuffix("ToolCall").ifBlank { "tool" }
    tool(stream, name, toolArgs)
  }

  private fun emitCodex(
    stream: AgentRunOutputStream,
    event: JsonNode,
  ) {
    when (event.path("type").asText()) {
      "agent_message" -> text(stream, event.path("message").asText(event.path("text").asText("")))
      in TOOL_EVENTS, "command_execution" ->
        tool(
          stream,
          event.path("name").asText("command"),
          event.path("input").takeUnless { it.isMissingNode }
            ?: event.path("command"),
        )
      "mcp_tool_call", "file_change", "web_search" ->
        emitCodexToolCall(stream, event, event.path("type").asText(), requiresItemId = false)
      "item.started", "item.updated", "item.completed" -> {
        val item = event.path("item")
        val itemType = item.path("type").asText("")
        if (itemType in CODEX_TOOL_EVENTS) {
          emitCodexToolCall(stream, item, itemType)
        } else if (itemType == "agent_message") {
          text(stream, item.path("text").asText(""))
        } else if (itemType !in CODEX_OMITTED_ITEM_TYPES) {
          diagnostics.warning("seam=standalone_output_decode value_expected=codex_item value_used=omitted")
        }
      }
      else -> fallback(event)
    }
  }

  private fun emitCodexToolCall(
    stream: AgentRunOutputStream,
    item: JsonNode,
    itemType: String,
    requiresItemId: Boolean = true,
  ) {
    val id = item.path("id").asText("")
    val name: String
    val input: JsonNode
    when (itemType) {
      "mcp_tool_call" -> {
        val server = item.path("server").asText("")
        val toolName = item.path("tool").asText("")
        if (server.isBlank() || toolName.isBlank()) {
          diagnostics.warning("seam=standalone_output_decode value_expected=codex_mcp_tool value_used=omitted")
          return
        }
        name = "mcp__${server}__$toolName"
        input = item.path("arguments")
      }
      "file_change" -> {
        name = "edit"
        input = codexChanges(item)
      }
      "web_search" -> {
        name = "web_search"
        input = item.path("query")
      }
      "command_execution" -> {
        name = "command"
        input = item.path("command")
      }
      else -> {
        name = item.path("name").asText(itemType)
        input = item.path("input").takeUnless(JsonNode::isMissingNode) ?: item.path("arguments")
      }
    }
    if ((requiresItemId && id.isBlank()) || !hasUsefulInput(input)) {
      diagnostics.warning("seam=standalone_output_decode value_expected=codex_tool_input value_used=omitted")
      return
    }
    if (id.isNotBlank() && !rememberCall(emittedCodexCalls, id)) return
    tool(stream, name, input)
  }

  private fun codexChanges(item: JsonNode): JsonNode {
    val changes = mapper.createArrayNode()
    val changeItems = item.path("changes")
    if (changeItems.isArray) {
      changeItems.forEach { change ->
        val shown = mapper.createObjectNode()
        change.path("kind").takeUnless { it.isMissingNode }?.let { shown.set<JsonNode>("kind", it) }
        change.path("path").takeUnless { it.isMissingNode }?.let { shown.set<JsonNode>("path", it) }
        changes.add(shown)
      }
    }
    return changes
  }

  private fun cursorPartial(
    stream: AgentRunOutputStream,
    delta: String,
  ) {
    if (delta.isEmpty()) return
    if (!cursorPartialMessageOverflowed) {
      if (cursorPartialMessage.length + delta.length <= MAX_EVENT_CHARS) {
        cursorPartialMessage.append(delta)
      } else {
        cursorPartialMessage.setLength(0)
        cursorPartialMessageOverflowed = true
        diagnostics.warning("seam=standalone_output_decode value_expected=bounded_partial_message value_used=truncated")
      }
    }
    delta.forEach { char ->
      if (droppingCursorPartialLine) {
        if (char == '\n') {
          emitCursorPartialLine(stream)
          droppingCursorPartialLine = false
        }
      } else if (char == '\n') {
        emitCursorPartialLine(stream)
      } else if (cursorPartialLine.length < MAX_LINE_CHARS) {
        cursorPartialLine.append(char)
      } else {
        droppingCursorPartialLine = true
        diagnostics.warning("seam=standalone_output_decode value_expected=bounded_partial_line value_used=truncated")
      }
    }
    emitCursorPartialText(stream)
  }

  private fun flushCursorPartial(stream: AgentRunOutputStream) {
    if (cursorPartialLine.isNotEmpty()) {
      emitCursorPartialLine(stream)
    } else if (cursorPartialTextPending) {
      delegate.write(stream, "\n")
      cursorPartialTextPending = false
    }
    droppingCursorPartialLine = false
    cursorPartialMessage.setLength(0)
    cursorPartialMessageOverflowed = false
  }

  private fun emitCursorPartialLine(stream: AgentRunOutputStream) {
    emitCursorPartialText(stream)
    delegate.write(stream, "\n")
    cursorPartialTextPending = false
  }

  private fun emitCursorPartialText(stream: AgentRunOutputStream) {
    if (cursorPartialLine.isEmpty()) return
    delegate.write(stream, cursorPartialLine.toString())
    cursorPartialLine.setLength(0)
    cursorPartialTextPending = true
  }

  private fun hasUsefulInput(input: JsonNode?): Boolean =
    input != null &&
      !input.isMissingNode &&
      !input.isNull &&
      input.toString().isNotBlank() &&
      input.toString() != "{}" &&
      input.toString() != "[]"

  private fun rememberCall(
    seen: MutableSet<String>,
    id: String,
  ): Boolean {
    if (id in seen) return false
    if (id.length > MAX_CALL_ID_CHARS) {
      diagnostics.warning("seam=standalone_output_decode value_expected=bounded_call_id value_used=omitted")
      return false
    }
    if (seen.size >= MAX_TRACKED_CALLS) {
      if (!toolTrackingOverflowReported) {
        toolTrackingOverflowReported = true
        diagnostics.warning("seam=standalone_output_decode value_expected=bounded_tool_calls value_used=omitted")
      }
      return false
    }
    seen.add(id)
    return true
  }

  private fun text(
    stream: AgentRunOutputStream,
    value: String,
  ) {
    value.lineSequence().forEach { line ->
      if (line.isNotBlank()) delegate.write(stream, "${line.take(MAX_LINE_CHARS)}\n")
    }
  }

  private fun tool(
    stream: AgentRunOutputStream,
    name: String,
    input: JsonNode,
  ) {
    val usefulInput = input.takeUnless { it.isMissingNode }?.toString()?.take(MAX_TOOL_INPUT_CHARS).orEmpty()
    val suffix = if (usefulInput.isBlank() || usefulInput == "{}") "" else " $usefulInput"
    delegate.write(stream, "Tool ${name.take(MAX_TOOL_NAME_CHARS)}$suffix\n")
  }

  private fun fallback(event: JsonNode) {
    val type = event.path("type").asText("unknown").take(MAX_EVENT_TYPE_CHARS)
    diagnostics.warning("seam=standalone_output_decode value_expected=known_event value_used=omitted:$type")
  }

  companion object {
    const val MAX_EVENT_CHARS = 65_536
    const val MAX_LINE_CHARS = 1_536
    const val MAX_TOOL_NAME_CHARS = 100
    const val MAX_TOOL_INPUT_CHARS = 240
    const val MAX_EVENT_TYPE_CHARS = 80
    const val MAX_TRACKED_CALLS = 512
    const val MAX_CALL_ID_CHARS = 256
    const val STREAM_END = "\u0000"
    val OMITTED_EVENTS =
      setOf(
        "result",
        "tool_result",
        "system",
        "user",
        "init",
        "message_start",
        "message_delta",
        "turn.completed",
        "thread.started",
      )
    val TOOL_EVENTS = setOf("tool_use", "tool_call", "function_call")
    val CODEX_TOOL_EVENTS = TOOL_EVENTS + setOf("command_execution", "mcp_tool_call", "file_change", "web_search")
    val CODEX_OMITTED_ITEM_TYPES = setOf("reasoning", "todo_list")
  }
}
