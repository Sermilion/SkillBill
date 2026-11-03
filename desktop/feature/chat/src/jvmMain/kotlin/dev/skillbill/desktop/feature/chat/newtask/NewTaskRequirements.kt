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

/** True when the Goal run card should appear. A Q&A turn is not enough; the operator has to be asking to run a goal, or the latest reply has to offer one. */
internal fun showGoalOfferCard(firstTurnEnded: Boolean, messages: List<ChatMessage>, offer: NewTaskOffer): Boolean {
  if (!firstTurnEnded) return false
  if (offer.prefixKind != null || offer.problem != null || offer.started != null) return true
  if (operatorStandsAside(messages.filterIsInstance<ChatMessage.Operator>().lastOrNull()?.text)) return false
  return goalOfferRelevant(messages)
}

/** True when the latest reply offers a start other than a goal or plan, so that action is the offer and the goal card stays out of its way. */
internal fun offersStandaloneStart(messages: List<ChatMessage>): Boolean =
  messages.filterIsInstance<ChatMessage.Agent>().lastOrNull()?.actions.orEmpty().any {
    val start = it.start
    start != null && start != NewTaskActionKind.Goal && start != NewTaskActionKind.Plan
  }

internal fun offersGoalOrPlanStart(messages: List<ChatMessage>): Boolean =
  messages.filterIsInstance<ChatMessage.Agent>().lastOrNull()?.actions.orEmpty().any {
    it.start == NewTaskActionKind.Goal || it.start == NewTaskActionKind.Plan
  }

internal fun asksToRunGoal(text: String): Boolean {
  val normalized = text.trim().lowercase()
  if (normalized.isEmpty()) return false
  if (previewRoute(normalized) == NewTaskRoutePreview.Plan) return true
  return GOAL_RUN.containsMatchIn(normalized) ||
    START_OR_RUN_GOAL.containsMatchIn(normalized) ||
    IMPLEMENT_THIS.containsMatchIn(normalized)
}

private fun goalOfferRelevant(messages: List<ChatMessage>): Boolean {
  if (offersGoalOrPlanStart(messages)) return true
  val askIndex = messages.indexOfLast { it is ChatMessage.Operator && asksToRunGoal(it.text) }
  if (askIndex < 0) return false
  return !offersStandaloneStart(messages.subList(askIndex + 1, messages.size))
}

private fun operatorStandsAside(text: String?): Boolean =
  when (text?.let(::previewRoute)) {
    NewTaskRoutePreview.Review,
    NewTaskRoutePreview.Validate,
    NewTaskRoutePreview.Qa,
    NewTaskRoutePreview.Pr,
    NewTaskRoutePreview.Release,
    -> true
    else -> false
  }

private val GOAL_RUN = Regex("""\bgoal\s+run\b""")
private val START_OR_RUN_GOAL = Regex("""\b(?:start|run)\b(?:\s+\S+){0,4}\s+goal\b""")
private val IMPLEMENT_THIS = Regex("""\b(?:implement|ship|build)\s+(?:this|it|that)\b""")

internal fun newTaskTitle(messages: List<ChatMessage>): String? =
  messages.asReversed()
    .asSequence()
    .filterIsInstance<ChatMessage.Agent>()
    .filterNot { it.streaming }
    .flatMap { it.actions }
    .filter { it.start == NewTaskActionKind.Goal || it.start == NewTaskActionKind.Plan }
    .firstNotNullOfOrNull { it.title }
