package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val ACTION_MARKER = "```skillbill-actions"

internal class ChatReplyStream(private val diagnostics: Diagnostics) {
  private var pending = ""
  private var metadata: StringBuilder? = null

  fun accept(event: ChatEvent): List<ChatEvent> = when (event) {
    is ChatEvent.TextDelta -> text(event.text)
    ChatEvent.Completed -> buildList {
      val actions = metadata
      if (actions == null) {
        if (pending.isNotEmpty()) add(ChatEvent.TextDelta(pending))
      } else {
        val decoded = decodeActions(actions.toString())
        if (decoded == null) diagnostics.record("chat.actions_invalid")
        else if (decoded.isNotEmpty()) add(ChatEvent.SuggestedActions(decoded))
      }
      pending = ""
      add(event)
    }
    is ChatEvent.Failed -> buildList {
      if (metadata == null && pending.isNotEmpty()) add(ChatEvent.TextDelta(pending))
      pending = ""
      add(event)
    }
    else -> listOf(event)
  }

  private fun text(delta: String): List<ChatEvent> {
    metadata?.let { it.append(delta); return emptyList() }
    pending += delta
    val marker = pending.indexOf(ACTION_MARKER)
    if (marker >= 0) {
      val visible = pending.substring(0, marker)
      metadata = StringBuilder(pending.substring(marker + ACTION_MARKER.length))
      pending = ""
      return visible.takeIf { it.isNotEmpty() }?.let { listOf(ChatEvent.TextDelta(it)) }.orEmpty()
    }
    val held = (minOf(pending.length, ACTION_MARKER.length - 1) downTo 1)
      .firstOrNull { pending.endsWith(ACTION_MARKER.take(it)) } ?: 0
    val visible = pending.dropLast(held)
    pending = pending.takeLast(held)
    return visible.takeIf { it.isNotEmpty() }?.let { listOf(ChatEvent.TextDelta(it)) }.orEmpty()
  }
}

private fun decodeActions(source: String): List<ChatSuggestedAction>? {
  val trimmed = source.trim()
  if (!trimmed.endsWith("```") || trimmed.length > 16000) return null
  val array = try {
    Json.parseToJsonElement(trimmed.removeSuffix("```").trim()) as? JsonArray
  } catch (_: SerializationException) {
    null
  } ?: return null
  if (array.size > 3) return null
  val actions = array.map { element ->
    val action = element as? JsonObject ?: return null
    val label = action.actionString("label")?.trim()?.takeIf { it.isNotBlank() && it.length <= 60 && '\n' !in it && '\r' !in it } ?: return null
    val instruction = action.actionString("instruction")?.trim()?.takeIf { it.isNotBlank() && it.length <= 2000 } ?: return null
    val mode = when (action.actionString("mode")) {
      "read" -> ChatPermissionMode.ReadOnly
      "act" -> ChatPermissionMode.Act
      else -> return null
    }
    ChatSuggestedAction(label, instruction, mode)
  }
  return actions.takeIf { it.map(ChatSuggestedAction::label).distinct().size == it.size }
}

private fun JsonObject.actionString(key: String): String? =
  (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
