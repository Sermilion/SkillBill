package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.feature.chat.ChatMessage

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
