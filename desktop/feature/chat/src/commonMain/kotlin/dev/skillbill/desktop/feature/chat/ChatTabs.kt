package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.WorkKind

/** One line of a chat transcript. */
sealed interface ChatMessage {
  /** The templated execution state shown before the agent answers. */
  data class StateLine(val text: String) : ChatMessage

  data class Operator(val text: String) : ChatMessage

  /** An agent answer; [streaming] while its turn runs, [tools] in the order the agent used them. */
  data class Agent(val text: String, val streaming: Boolean, val tools: List<String>) : ChatMessage

  data class Notice(val text: String) : ChatMessage

  data class Error(val failure: ChatFailure?, val text: String) : ChatMessage
}

/** One chat tab, keyed by the execution's `WorkItem.id`. */
data class ChatTab(val executionId: String, val title: String, val kind: WorkKind?)

/** The window's chat tabs in opening order. It lives in memory only; no chat survives the window. */
data class ChatTabsState(val tabs: List<ChatTab> = emptyList(), val activeId: String? = null)

/** Appends and activates [tab]; an id that already has a tab is only activated. */
internal fun ChatTabsState.withTab(tab: ChatTab): ChatTabsState =
  if (tabs.any { it.executionId == tab.executionId }) activated(tab.executionId) else copy(tabs = tabs + tab, activeId = tab.executionId)

internal fun ChatTabsState.activated(id: String): ChatTabsState = if (tabs.any { it.executionId == id }) copy(activeId = id) else this

/** Closing the active tab activates the tab before it, else the new first one; closing the last tab gives the empty state. */
internal fun ChatTabsState.without(id: String): ChatTabsState {
  val index = tabs.indexOfFirst { it.executionId == id }
  if (index < 0) return this
  val remaining = tabs.filterNot { it.executionId == id }
  if (remaining.isEmpty()) return ChatTabsState()
  val active = activeId?.takeIf { it != id } ?: tabs.getOrNull(index - 1)?.executionId ?: remaining.first().executionId
  return copy(tabs = remaining, activeId = active)
}
