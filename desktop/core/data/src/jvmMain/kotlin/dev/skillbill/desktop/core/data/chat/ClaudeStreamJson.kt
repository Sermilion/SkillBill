package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val toolJson = Json { prettyPrint = true }

/** Decodes one line of `claude --output-format stream-json`; text blocks in `assistant` lines repeat the deltas, so they are skipped. */
internal fun decodeClaudeLine(line: String): List<ChatEvent> {
  if (line.isBlank()) return emptyList()
  val root = try {
    Json.parseToJsonElement(line) as? JsonObject
  } catch (_: SerializationException) {
    null
  } ?: return listOf(ChatEvent.Failed(ChatFailure.StreamMalformed, line.take(200)))
  return when (root.string("type")) {
    "stream_event" -> {
      val delta = root.obj("event")?.obj("delta")
      val text = delta?.string("text")
      if (delta?.string("type") == "text_delta" && text != null) listOf(ChatEvent.TextDelta(text)) else emptyList()
    }
    "assistant" -> root.contentBlocks().mapNotNull { block ->
      if (block.string("type") != "tool_use") return@mapNotNull null
      val name = block.string("name") ?: return@mapNotNull null
      val id = block.string("id") ?: return@mapNotNull null
      val input = block["input"]
      val summary = block.obj("input")?.let {
        it.string("description") ?: it.string("command") ?: it.string("file_path") ?: it.string("pattern")
      }
      ChatEvent.ToolUse(ChatToolCall(id, name, input?.renderToolContent().orEmpty(), summary))
    }
    "user" -> root.contentBlocks().mapNotNull { block ->
      if (block.string("type") != "tool_result") return@mapNotNull null
      val id = block.string("tool_use_id") ?: return@mapNotNull null
      ChatEvent.ToolResult(
        id,
        block["content"]?.renderToolContent().orEmpty(),
        (block["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
      )
    }
    "result" -> when ((root["is_error"] as? JsonPrimitive)?.booleanOrNull) {
      false -> listOf(ChatEvent.Completed)
      true -> listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, root.string("result") ?: root.string("subtype")))
      null -> emptyList()
    }
    else -> emptyList()
  }
}

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.contentBlocks(): List<JsonObject> =
  (obj("message")?.get("content") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonElement.renderToolContent(): String = when (this) {
  is JsonPrimitive -> if (isString) content else toString()
  is JsonArray -> joinToString("\n") { element ->
    (element as? JsonObject)?.string("text") ?: toolJson.encodeToString(JsonElement.serializer(), element)
  }
  else -> toolJson.encodeToString(JsonElement.serializer(), this)
}
