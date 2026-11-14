package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.VerifyQaChoice
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import dev.skillbill.desktop.core.domain.model.VerifyReviewModeChoice
import dev.skillbill.desktop.core.domain.model.VerifyStartChoices

private const val ACTION_MARKER = "```skillbill-actions"
private const val REPOSITORY_MARKER = RepositoryCandidatePayloadKeys.FENCE
private const val REPOSITORY_NOTICE = "\n\n_Sona could not use the repository selection block. Send one valid repository path block at the end of a reply._"

internal class ChatReplyStream(
  private val diagnostics: Diagnostics,
  private val allowRepositoryCandidates: Boolean = true,
) {
  private var pending = ""
  private var metadata: StringBuilder? = null
  private var actionPayload: StringBuilder? = null
  private var actionBlockOpen = false
  private var repositoryMetadata = false
  private var repositoryOverflow = false
  private var repositoryBlockOpen = false
  private var repositoryBlockCount = 0
  private var repositoryCandidateBody: String? = null
  private var repositoryInvalid = false
  private var repositoryTrailingInvalid = false
  private var actionsSeen = false
  private val deferredActionEvents = mutableListOf<ChatEvent>()

  fun accept(event: ChatEvent): List<ChatEvent> = when (event) {
    is ChatEvent.TextDelta -> text(event.text)
    is ChatEvent.Notice -> listOf(event)
    ChatEvent.MessageStarted, ChatEvent.Completed -> buildList {
      if (event == ChatEvent.MessageStarted && repositoryMetadata) {
        repositoryInvalid = true
        if (repositoryBlockOpen) {
          metadata = null
          repositoryBlockOpen = false
        }
      }
      if (event == ChatEvent.MessageStarted && repositoryMarkerPrefixPending()) {
        add(event)
        return@buildList
      }
      if (actionBlockOpen && event == ChatEvent.MessageStarted) {
        if (!repositoryMetadata) {
          diagnostics.record("chat.actions_invalid")
          deferredActionEvents += ChatEvent.TextDelta(droppedActionsNotice(listOf("the action block could not be read (bad JSON, more than 3 actions, or duplicate labels)")))
        }
        actionPayload = null
        actionBlockOpen = false
        add(event)
        return@buildList
      }
      if (event == ChatEvent.MessageStarted && actionPayload != null && !repositoryMetadata) {
        val decoded = decodeActions(actionPayload.toString(), diagnostics)
        if (decoded == null) {
          diagnostics.record("chat.actions_invalid")
          deferredActionEvents += ChatEvent.TextDelta(droppedActionsNotice(listOf("the action block could not be read (bad JSON, more than 3 actions, or duplicate labels)")))
        } else {
          if (decoded.dropped.isNotEmpty()) deferredActionEvents += ChatEvent.TextDelta(droppedActionsNotice(decoded.dropped))
          if (decoded.actions.isNotEmpty()) deferredActionEvents += ChatEvent.SuggestedActions(decoded.actions)
        }
        actionPayload = null
        pending = ""
        add(event)
        return@buildList
      }
      if (event == ChatEvent.Completed && repositoryMetadata) {
        if (pending.isNotEmpty()) {
          if (pending.any { !it.isWhitespace() }) repositoryTrailingInvalid = true
          add(ChatEvent.TextDelta(pending))
          pending = ""
        }
        val candidate = if (
          !repositoryBlockOpen && repositoryBlockCount == 1 && !repositoryOverflow && !actionsSeen &&
          !repositoryInvalid && !repositoryTrailingInvalid
        ) repositoryCandidateBody?.let(::decodeRepositoryCandidate) else null
        if (candidate != null && allowRepositoryCandidates) add(ChatEvent.RepositoryCandidate(candidate))
        else {
          diagnostics.record(if (repositoryOverflow) "chat.repository_candidate_oversized" else "chat.repository_candidate_invalid")
          add(ChatEvent.TextDelta(REPOSITORY_NOTICE))
        }
        metadata = null
        repositoryMetadata = false
        repositoryOverflow = false
        repositoryBlockOpen = false
        repositoryBlockCount = 0
        repositoryCandidateBody = null
        repositoryInvalid = false
        repositoryTrailingInvalid = false
        actionPayload = null
        actionBlockOpen = false
        actionsSeen = false
        deferredActionEvents.clear()
        pending = ""
        add(event)
        return@buildList
      }
      if (repositoryBlockOpen) {
        add(event)
        return@buildList
      }
      val actions = actionPayload.takeUnless { repositoryMetadata }
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
      actionPayload = null
      if (event == ChatEvent.Completed) {
        addAll(deferredActionEvents)
        deferredActionEvents.clear()
        actionsSeen = false
      }
      add(event)
    }
    is ChatEvent.Failed -> buildList {
      if (pending.isNotEmpty()) add(ChatEvent.TextDelta(pending))
      pending = ""
      metadata = null
      actionPayload = null
      actionBlockOpen = false
      repositoryMetadata = false
      repositoryOverflow = false
      repositoryBlockOpen = false
      repositoryBlockCount = 0
      repositoryCandidateBody = null
      repositoryInvalid = false
      repositoryTrailingInvalid = false
      actionsSeen = false
      deferredActionEvents.clear()
      add(event)
    }
    else -> listOf(event)
  }

  private fun text(delta: String): List<ChatEvent> {
    if (actionBlockOpen) return consumeActionBlock(delta)
    if (repositoryBlockOpen) return repositoryBlock(delta)
    return scanOrdinaryText(delta)
  }

  private fun repositoryBlock(delta: String): List<ChatEvent> {
    val block = metadata ?: StringBuilder().also { metadata = it }
    val combined = block.toString() + delta
    val closing = combined.indexOf("```")
    if (closing < 0) {
      if (repositoryOverflow) {
        block.setLength(0)
        val suffixLength = combined.takeLastWhile { it == '`' }.length.coerceAtMost(2)
        if (suffixLength > 0) block.append(combined.takeLast(suffixLength))
      } else appendRepositoryPayload(block, delta)
      return emptyList()
    }
    val body = combined.substring(0, closing)
    block.setLength(0)
    appendRepositoryPayload(block, body)
    val closeBytes = 3
    val totalBytes = REPOSITORY_MARKER.toByteArray(Charsets.UTF_8).size + block.toString().toByteArray(Charsets.UTF_8).size + closeBytes
    if (totalBytes > RepositoryCandidatePayloadKeys.MAX_BLOCK_BYTES) repositoryOverflow = true
    if (!repositoryOverflow && repositoryCandidateBody == null) repositoryCandidateBody = block.toString()
    metadata = null
    repositoryBlockOpen = false
    val trailing = combined.substring(closing + closeBytes)
    repositoryTrailingInvalid = repositoryTrailingInvalid || trailing.any { !it.isWhitespace() }
    return scanOrdinaryText(trailing)
  }

  private fun appendRepositoryPayload(block: StringBuilder, value: String) {
    val currentBytes = block.toString().toByteArray(Charsets.UTF_8).size
    val remaining = RepositoryCandidatePayloadKeys.MAX_BLOCK_BYTES -
      REPOSITORY_MARKER.toByteArray(Charsets.UTF_8).size - currentBytes - 3
    if (remaining < 0 || value.toByteArray(Charsets.UTF_8).size > remaining) {
      repositoryOverflow = true
      block.setLength(0)
      val suffixLength = value.takeLastWhile { it == '`' }.length.coerceAtMost(2)
      if (suffixLength > 0) block.append(value.takeLast(suffixLength))
    } else if (!repositoryOverflow) block.append(value)
  }

  private fun scanOrdinaryText(delta: String): List<ChatEvent> {
    pending += delta
    val actionAt = pending.indexOf(ACTION_MARKER)
    val repositoryAt = pending.indexOf(REPOSITORY_MARKER)
    val marker = listOf(actionAt, repositoryAt).filter { it >= 0 }.minOrNull()
    if (marker != null) {
      val visible = pending.substring(0, marker)
      if (repositoryMetadata && visible.any { !it.isWhitespace() }) repositoryTrailingInvalid = true
      val repository = repositoryAt == marker
      if (repository) {
        repositoryMetadata = true
        repositoryBlockCount++
        if (repositoryBlockCount > 1 || actionsSeen) repositoryInvalid = true
        repositoryBlockOpen = true
      } else {
        actionsSeen = true
        if (repositoryMetadata) repositoryInvalid = true
        actionPayload = StringBuilder()
        actionBlockOpen = true
      }
      val rest = pending.substring(marker + if (repository) REPOSITORY_MARKER.length else ACTION_MARKER.length)
      pending = ""
      val emitted = visible.takeIf { it.isNotEmpty() }?.let { listOf(ChatEvent.TextDelta(it)) }.orEmpty()
      return emitted + if (repository) repositoryBlock(rest) else {
        consumeActionBlock(rest)
      }
    }
    val held = (minOf(pending.length, maxOf(ACTION_MARKER.length, REPOSITORY_MARKER.length) - 1) downTo 1)
      .firstOrNull { pending.endsWith(ACTION_MARKER.take(it)) || pending.endsWith(REPOSITORY_MARKER.take(it)) } ?: 0
    val visible = pending.dropLast(held)
    pending = pending.takeLast(held)
    if (repositoryMetadata && visible.any { !it.isWhitespace() }) repositoryTrailingInvalid = true
    return visible.takeIf { it.isNotEmpty() }?.let { listOf(ChatEvent.TextDelta(it)) }.orEmpty()
  }

  private fun repositoryMarkerPrefixPending(): Boolean =
    pending.isNotEmpty() && REPOSITORY_MARKER.startsWith(pending)

  private fun consumeActionBlock(delta: String): List<ChatEvent> {
    val block = actionPayload ?: StringBuilder().also { actionPayload = it }
    val combined = block.toString() + delta
    val closing = combined.indexOf("```")
    if (closing < 0) {
      block.append(delta)
      return emptyList()
    }
    block.setLength(0)
    block.append(combined.substring(0, closing + 3))
    actionBlockOpen = false
    return scanOrdinaryText(combined.substring(closing + 3))
  }
}

private fun decodeRepositoryCandidate(source: String): String? {
  val body = source.trim()
  if (REPOSITORY_MARKER in body || ACTION_MARKER in body) return null
  val json = try {
    Json.parseToJsonElement(body) as? JsonObject
  } catch (_: SerializationException) {
    null
  } ?: return null
  if (json.keys != setOf(RepositoryCandidatePayloadKeys.CONTRACT_VERSION, RepositoryCandidatePayloadKeys.PATH)) return null
  val version = (json[RepositoryCandidatePayloadKeys.CONTRACT_VERSION] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content
  if (version != RepositoryCandidatePayloadKeys.CONTRACT_VERSION_VALUE) return null
  val path = (json[RepositoryCandidatePayloadKeys.PATH] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: return null
  if ('\u0000' in path || '\r' in path || '\n' in path) return null
  return path.takeIf { runCatching { Path.of(it).isAbsolute }.getOrDefault(false) }
}

private const val MAX_LABEL = 60

private class DecodedActions(val actions: List<ChatSuggestedAction>, val dropped: List<String>)

private fun droppedActionsNotice(reasons: List<String>): String {
  val count = if (reasons.size == 1) "a suggested action" else "${reasons.size} suggested actions"
  return "\n\n_Sona could not show $count: ${reasons.distinct().joinToString("; ")}. Ask the agent to send it again._"
}

private fun decodeActions(source: String, diagnostics: Diagnostics): DecodedActions? {
  val trimmed = source.trim()
  if (!trimmed.endsWith("```")) return null
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
  val instruction = action.actionString("instruction")?.trim()?.takeIf { it.isNotBlank() }
    ?: return ActionResult.Dropped("\"$label\" has no instruction")
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
  val verify = when (val choices = verifyChoices(action, label, start)) {
    is VerifyChoicesResult.Dropped -> return choices.dropped
    is VerifyChoicesResult.Ok -> choices.choices
  }
  return ActionResult.Ok(
    ChatSuggestedAction(label, instruction, mode, start, actionTitle(action, diagnostics), target.value, branch.value, verify),
  )
}

private sealed interface VerifyChoicesResult {
  class Ok(val choices: VerifyStartChoices) : VerifyChoicesResult
  class Dropped(val dropped: ActionResult.Dropped) : VerifyChoicesResult
}

private fun verifyChoices(action: JsonObject, label: String, start: NewTaskActionKind?): VerifyChoicesResult {
  if (QA_KEY !in action && REVIEW_MODE_KEY !in action) return VerifyChoicesResult.Ok(VerifyStartChoices())
  if (start != NewTaskActionKind.Verify) {
    return VerifyChoicesResult.Dropped(ActionResult.Dropped("\"$label\" sets qa or review_mode, which only a verify start takes"))
  }
  val qa = action[QA_KEY]?.let {
    VerifyQaChoice.entries.firstOrNull { choice -> choice.wire == action.actionString(QA_KEY) }
      ?: return VerifyChoicesResult.Dropped(ActionResult.Dropped("\"$label\" has a qa other than on or off"))
  }
  val reviewMode = action[REVIEW_MODE_KEY]?.let {
    VerifyReviewModeChoice.entries.firstOrNull { choice -> choice.wire == action.actionString(REVIEW_MODE_KEY) }
      ?: return VerifyChoicesResult.Dropped(ActionResult.Dropped("\"$label\" has a review_mode other than inline or delegated"))
  }
  return VerifyChoicesResult.Ok(VerifyStartChoices(qa, reviewMode))
}

private const val QA_KEY = "qa"
private const val REVIEW_MODE_KEY = "review_mode"

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
