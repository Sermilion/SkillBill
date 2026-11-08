package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val ACTION_MARKER = "```skillbill-actions"

internal class ChatReplyStream(private val diagnostics: Diagnostics) {
  private var pending = ""
  private var metadata: StringBuilder? = null

  fun accept(event: ChatEvent): List<ChatEvent> = when (event) {
    is ChatEvent.TextDelta -> text(event.text)
    is ChatEvent.Notice -> listOf(event)
    ChatEvent.MessageStarted, ChatEvent.Completed -> buildList {
      val actions = metadata
      if (actions == null) {
        if (pending.isNotEmpty()) add(ChatEvent.TextDelta(pending))
      } else {
        val decoded = decodeActions(actions.toString(), diagnostics)
        if (decoded == null) {
          diagnostics.record("chat.actions_invalid")
          add(ChatEvent.TextDelta(droppedActionsNotice(listOf("the action block could not be read (bad JSON, more than 3 actions, or duplicate labels)"))))
        } else {
          if (decoded.dropped.isNotEmpty()) add(ChatEvent.TextDelta(droppedActionsNotice(decoded.dropped)))
          if (decoded.actions.isNotEmpty()) add(ChatEvent.SuggestedActions(decoded.actions))
        }
      }
      pending = ""
      metadata = null
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

private const val MAX_LABEL = 60
private const val MAX_INSTRUCTION = 2000

private class DecodedActions(val actions: List<ChatSuggestedAction>, val dropped: List<String>)

private fun droppedActionsNotice(reasons: List<String>): String {
  val count = if (reasons.size == 1) "a suggested action" else "${reasons.size} suggested actions"
  return "\n\n_Skill Bill could not show $count: ${reasons.distinct().joinToString("; ")}. Ask the agent to send it again._"
}

private fun decodeActions(source: String, diagnostics: Diagnostics): DecodedActions? {
  val trimmed = source.trim()
  if (!trimmed.endsWith("```") || trimmed.length > 16000) return null
  val array = try {
    Json.parseToJsonElement(trimmed.removeSuffix("```").trim()) as? JsonArray
  } catch (_: SerializationException) {
    null
  } ?: return null
  if (array.size > 3) return null
  val dropped = mutableListOf<String>()
  val actions = array.mapNotNull { element ->
    when (val result = decodeAction(element, diagnostics)) {
      is ActionResult.Ok -> result.action
      is ActionResult.Dropped -> {
        diagnostics.record(result.diagnostic)
        dropped += result.reason
        null
      }
    }
  }
  if (actions.map(ChatSuggestedAction::label).distinct().size != actions.size) return null
  return DecodedActions(actions, dropped)
}

private sealed interface ActionResult {
  class Ok(val action: ChatSuggestedAction) : ActionResult
  class Dropped(val reason: String, val diagnostic: String = "chat.action_invalid") : ActionResult
}

private fun decodeAction(element: JsonElement, diagnostics: Diagnostics): ActionResult {
  val action = element as? JsonObject ?: return ActionResult.Dropped("an entry is not a JSON object")
  val rawLabel = action.actionString("label")?.trim()
  val label = rawLabel?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL && '\n' !in it && '\r' !in it }
    ?: return ActionResult.Dropped(
      if (rawLabel != null && rawLabel.length > MAX_LABEL) "label over $MAX_LABEL characters" else "missing or invalid label",
    )
  val rawInstruction = action.actionString("instruction")?.trim()
  val instruction = rawInstruction?.takeIf { it.isNotBlank() && it.length <= MAX_INSTRUCTION }
    ?: return ActionResult.Dropped(
      if (rawInstruction != null && rawInstruction.length > MAX_INSTRUCTION) {
        "\"$label\" has an instruction of ${rawInstruction.length} characters, over the $MAX_INSTRUCTION limit"
      } else {
        "\"$label\" has no instruction"
      },
    )
  val mode = when (action.actionString("mode")) {
    "read" -> ChatPermissionMode.ReadOnly
    "act" -> ChatPermissionMode.Act
    else -> return ActionResult.Dropped("\"$label\" has no valid mode")
  }
  val start = action["start"]?.let {
    val wire = action.actionString("start")
    NewTaskActionKind.entries.firstOrNull { it.wire == wire }
      ?: return ActionResult.Dropped("\"$label\" has an unknown start", "chat.action_start_unknown")
  }
  val target = optionalActionString(action, "target") ?: return ActionResult.Dropped("\"$label\" has a non-string target")
  val branch = optionalActionString(action, "branch") ?: return ActionResult.Dropped("\"$label\" has a non-string branch")
  return ActionResult.Ok(
    ChatSuggestedAction(label, instruction, mode, start, actionTitle(action, diagnostics), target.value, branch.value),
  )
}

private fun actionTitle(action: JsonObject, diagnostics: Diagnostics): String? {
  if ("title" !in action) return null
  val title = action.actionString("title")?.trim()?.takeIf { title ->
    title.isNotBlank() &&
      title.length <= 60 &&
      '\n' !in title &&
      '\r' !in title &&
      title.any { it.isLetterOrDigit() && it.code < 128 }
  }
  if (title == null) diagnostics.record("chat.action_title_invalid")
  return title
}

private class OptionalActionText(val value: String?)

private fun optionalActionString(action: JsonObject, key: String): OptionalActionText? =
  if (key !in action) OptionalActionText(null)
  else action.actionString(key)?.let { OptionalActionText(it.trim().takeIf(String::isNotBlank)) }

private fun JsonObject.actionString(key: String): String? =
  (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
