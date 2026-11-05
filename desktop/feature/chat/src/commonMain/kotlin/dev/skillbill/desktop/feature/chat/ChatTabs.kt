package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.OperationCardReference
import dev.skillbill.desktop.core.domain.model.OperationCardView
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlin.time.Instant

/** One tool call; [startedAt] is when the agent announced it and [finishedAt] when its result arrived. */
data class ChatToolEntry(
  val call: ChatToolCall,
  val result: ChatEvent.ToolResult? = null,
  val startedAt: Instant? = null,
  val finishedAt: Instant? = null,
)

/** One line of a chat transcript. */
sealed interface ChatMessage {
  /** The templated execution state shown before the agent answers; [detail] is the reason, stall time or progress. */
  data class StateLine(val text: String, val state: ExecutionState? = null, val phase: String? = null, val detail: String? = null) : ChatMessage

  /**
   * An operator message; [action] is the suggested action it came from, whose instruction is [text]. [images] were pasted with it
   * and live in memory for the session only.
   */
  data class Operator(val text: String, val action: ChatSuggestedAction? = null, val images: List<ChatImage> = emptyList()) : ChatMessage

  /** An agent answer; [streaming] while its turn runs, [tools] in the order the agent used them. */
  data class Agent(
    val text: String,
    val streaming: Boolean,
    val tools: List<ChatToolEntry>,
    val actions: List<ChatSuggestedAction> = emptyList(),
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
  ) : ChatMessage

  data class Notice(
    val text: String,
    val confirmation: Boolean = false,
    val operationCard: OperationCardReference? = null,
  ) : ChatMessage

  /**
   * A permission request of the chat's process, shown with its complete stored record and current outcome. [card] is
   * replaced in place as the request changes. [restoredCopy] marks a card read back from saved history: it is a display
   * copy, so its controls never enable. [submitting] is the key of the decision this window sent and is still awaiting an
   * answer; [refusal] is why the runtime refused the last click.
   */
  data class Approval(
    val card: ApprovalCard,
    val restoredCopy: Boolean = false,
    val submitting: Boolean = false,
    val refusal: String? = null,
  ) : ChatMessage

  /**
   * The approval card of a parked operation, read from the runtime store. [card] is replaced in place as the run settles.
   * [restoredCopy] marks a card read back from saved history, whose controls stay off until the store confirms it. [choices]
   * maps a thread ordinal to the offered option the operator picked, [submitting] is a click sent from this window, [refusal]
   * is why the runtime refused the last click.
   */
  data class OperationCard(
    val card: OperationCardView,
    val restoredCopy: Boolean = false,
    val submitting: Boolean = false,
    val refusal: String? = null,
    val choices: Map<String, String> = emptyMap(),
    val choicesSaved: Boolean = false,
  ) : ChatMessage

  data class Error(val failure: ChatFailure?, val text: String) : ChatMessage
}

/**
 * One chat tab. [id] names the chat; an execution's first chat uses the execution's `WorkItem.id`, and every further chat
 * on it gets its own id. [ordinal] counts the execution's chats, so the second one shows as "SKILL-1 (2)".
 */
data class ChatTab(
  val id: String,
  val executionId: String,
  val title: String,
  val kind: WorkKind?,
  val ordinal: Int = 1,
  /** Set on a tab that lists one repository's approval requests instead of a chat; it is that repository's root. */
  val approvalsRepository: String? = null,
) {
  val label: String get() = if (ordinal > 1) "$title ($ordinal)" else title
}

/** The window's chat tabs in opening order. Tabs live in memory; a chat outlives the window only as a save `/resume` can restore. */
data class ChatTabsState(val tabs: List<ChatTab> = emptyList(), val activeId: String? = null) {
  /** The execution the active chat is about. */
  val activeExecutionId: String? get() = tabs.firstOrNull { it.id == activeId }?.executionId
}

/** Appends and activates [tab]; an id that already has a tab is only activated. */
internal fun ChatTabsState.withTab(tab: ChatTab): ChatTabsState =
  if (tabs.any { it.id == tab.id }) activated(tab.id) else copy(tabs = tabs + tab, activeId = tab.id)

internal fun ChatTabsState.activated(id: String): ChatTabsState = if (tabs.any { it.id == id }) copy(activeId = id) else this

/** The next [ChatTab.ordinal] for a new chat on [executionId]. */
internal fun ChatTabsState.nextOrdinal(executionId: String): Int =
  (tabs.filter { it.executionId == executionId }.maxOfOrNull { it.ordinal } ?: 0) + 1

/** Closing the active tab activates the tab before it, else the new first one; closing the last tab gives the empty state. */
internal fun ChatTabsState.without(id: String): ChatTabsState {
  val index = tabs.indexOfFirst { it.id == id }
  if (index < 0) return this
  val remaining = tabs.filterNot { it.id == id }
  if (remaining.isEmpty()) return ChatTabsState()
  val active = activeId?.takeIf { it != id } ?: tabs.getOrNull(index - 1)?.id ?: remaining.first().id
  return copy(tabs = remaining, activeId = active)
}
