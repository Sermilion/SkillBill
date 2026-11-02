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

/** True when the goal card would offer the wrong run: a standalone start is on offer, or the operator's latest message asks for a release. */
internal fun goalOfferStandsAside(messages: List<ChatMessage>): Boolean =
  offersStandaloneStart(messages) ||
    messages.filterIsInstance<ChatMessage.Operator>().lastOrNull()?.let { previewRoute(it.text) } == NewTaskRoutePreview.Release

/** True when the latest reply offers a start other than a goal or plan, so that action is the offer and the goal card stays out of its way. */
internal fun offersStandaloneStart(messages: List<ChatMessage>): Boolean =
  messages.filterIsInstance<ChatMessage.Agent>().lastOrNull()?.actions.orEmpty().any {
    val start = it.start
    start != null && start != NewTaskActionKind.Goal && start != NewTaskActionKind.Plan
  }
