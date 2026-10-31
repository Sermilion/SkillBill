package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
    "assistant" -> (root.obj("message")?.get("content") as? JsonArray).orEmpty()
      .mapNotNull { block -> (block as? JsonObject)?.takeIf { it.string("type") == "tool_use" }?.string("name") }
      .map(ChatEvent::ToolUse)
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
