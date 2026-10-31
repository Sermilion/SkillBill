package dev.skillbill.desktop.core.data.chat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val toolJson = Json { prettyPrint = true }

/** One stream line as an object, or null when it is not JSON or not an object. */
internal fun parseStreamLine(line: String): JsonObject? = try {
  Json.parseToJsonElement(line) as? JsonObject
} catch (_: SerializationException) {
  null
}

internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

/** Strings as they are, text blocks joined, anything else as indented JSON. */
internal fun JsonElement.renderToolContent(): String = when (this) {
  is JsonPrimitive -> if (isString) content else toString()
  is JsonArray -> joinToString("\n") { element ->
    (element as? JsonObject)?.string("text") ?: toolJson.encodeToString(JsonElement.serializer(), element)
  }
  else -> toolJson.encodeToString(JsonElement.serializer(), this)
}
