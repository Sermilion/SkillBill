package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.feature.chat.ChatMessage

internal val NEW_TASK_COMPLETE_KEY = Regex("(?i)[A-Z0-9]+-\\d+(?:\\.\\d+)?")
private val NEW_TASK_REUSE_WORDING = Regex("(?i)\\b(?:reuse|re-use|use again)\\b")

internal data class NewTaskOfferKeyEdit(
  val chat: NewTaskChat,
  val repositoryRoot: String,
  val value: String,
  val transcriptBoundary: Int,
  val operatorMessagesAtEdit: List<ChatMessage.Operator>,
)

internal fun newTaskCompleteKeyOrUrl(raw: String?): String? {
  val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
  if (NEW_TASK_COMPLETE_KEY.matches(value)) return value
  if (value.any(Char::isWhitespace) || "://" !in value) return null
  val suffix = value.substringAfter("://")
  val pathEnd = listOf(suffix.indexOf('?'), suffix.indexOf('#')).filter { it >= 0 }.minOrNull() ?: suffix.length
  val path = suffix.substring(0, pathEnd)
  return value.takeIf { path.split('/').drop(1).any(NEW_TASK_COMPLETE_KEY::matches) }
}

internal fun newTaskStartArgumentIdentity(raw: String?): String? {
  val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
  return value.takeIf { NEW_TASK_COMPLETE_KEY.matches(it) || ("://" in it && it.none(Char::isWhitespace)) }
}

internal fun newTaskOperatorIdentity(messages: List<ChatMessage>): String? {
  val operators = messages.filterIsInstance<ChatMessage.Operator>().filter { it.action == null || acceptsKeyChip(it) }
  for ((index, message) in operators.withIndex()) {
    val text = message.text.trim()
    if (NEW_TASK_COMPLETE_KEY.matches(text)) return text
    val tokens = text.split(Regex("\\s+"))
    val identity = tokens.firstNotNullOfOrNull { token ->
      token.takeIf { candidate ->
        (index == 0 && candidate == tokens.firstOrNull() && NEW_TASK_COMPLETE_KEY.matches(candidate)) ||
          ("://" in candidate && newTaskCompleteKeyOrUrl(candidate) != null)
      }
    }
    if (identity != null) return identity
  }
  return null
}

private fun acceptsKeyChip(message: ChatMessage.Operator): Boolean {
  val action = message.action ?: return false
  val key = action.instruction.trim()
  return action.start == null &&
    NEW_TASK_COMPLETE_KEY.matches(key) &&
    message.text.trim() == key &&
    action.label.split(Regex("\\s+")).any { it.equals(key, ignoreCase = true) }
}

internal fun newTaskReuseRequested(
  messages: List<ChatMessage>,
  identity: String,
  offerFieldValue: String?,
  fieldEdit: NewTaskOfferKeyEdit?,
  chat: NewTaskChat?,
  repositoryRoot: String?,
): Boolean {
  if (!NEW_TASK_COMPLETE_KEY.matches(identity)) return false
  if (messages.asSequence().filterIsInstance<ChatMessage.Operator>().any { it.action == null && it.containsReuseIdentity(identity) }) return true
  val edit = fieldEdit ?: return false
  if (chat == null || edit.chat !== chat || edit.repositoryRoot != repositoryRoot || edit.value != offerFieldValue) return false
  if (!edit.value.trim().equals(identity, ignoreCase = true) || !NEW_TASK_COMPLETE_KEY.matches(edit.value.trim())) return false
  if (edit.transcriptBoundary > messages.size) return false
  val operatorsAtBoundary = messages.take(edit.transcriptBoundary).filterIsInstance<ChatMessage.Operator>()
  if (operatorsAtBoundary != edit.operatorMessagesAtEdit) return false
  return operatorsAtBoundary.any { it.action == null && it.containsReuseIdentity(identity) }
}

private fun ChatMessage.Operator.containsReuseIdentity(identity: String): Boolean =
  NEW_TASK_REUSE_WORDING.containsMatchIn(text) && text.split(Regex("\\s+")).any { it.equals(identity, ignoreCase = true) }

internal fun newTaskRequirements(messages: List<ChatMessage>): String {
  val operatorMessages = messages.withIndex().filter { it.value is ChatMessage.Operator }
  if (operatorMessages.size == 1) return (operatorMessages.single().value as ChatMessage.Operator).text
  if (operatorMessages.isEmpty()) return ""
  val first = (operatorMessages.first().value as ChatMessage.Operator).text
  val context = operatorMessages.drop(1).mapIndexed { index, entry ->
    val previousIndex = operatorMessages[index].index
    val agentLines = messages.subList(previousIndex + 1, entry.index)
      .filterIsInstance<ChatMessage.Agent>()
      .filterNot { it.streaming }
      .map { it.text.trim() }
      .filter(String::isNotBlank)
    buildList {
      if (agentLines.isNotEmpty()) add("Agent: ${agentLines.joinToString("\n\n")}")
      add("Operator: ${(entry.value as ChatMessage.Operator).text}")
    }.joinToString("\n\n")
  }
  return "$first\n\nChat context. Agent lines are questions, not requirements.\n${context.joinToString("\n\n")}"
}

internal fun newTaskTitle(messages: List<ChatMessage>): String? =
  messages.asReversed()
    .asSequence()
    .filterIsInstance<ChatMessage.Agent>()
    .filterNot { it.streaming }
    .flatMap { it.actions }
    .filter { it.start == NewTaskActionKind.Goal || it.start == NewTaskActionKind.Plan }
    .firstNotNullOfOrNull { it.title }
