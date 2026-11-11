package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.OperationCardDetailsView
import dev.skillbill.desktop.core.domain.model.OperationCardReference
import dev.skillbill.desktop.core.domain.model.OperationCardView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

private const val SAVED_LINES = 10

internal const val STOPPED_NOTICE = "Stopped"

internal const val START_AGAIN_SUFFIX = "Send another message to start again."

internal enum class OperationCardDecision { Confirm, Dismiss, Adjust }

internal fun restoredDraft(queued: List<QueuedChatMessage>, draft: String): String =
  (queued.map { it.text } + listOf(draft).filter { it.isNotBlank() }).joinToString("\n\n")

/** One chat's messages and turn state, with or without an execution behind it. */
internal class ChatThread(private val clock: Clock) {
  private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val transcript: StateFlow<List<ChatMessage>> = messages.asStateFlow()

  val agentState = MutableStateFlow<ChatAgent?>(null)
  val agent: StateFlow<ChatAgent?> = agentState.asStateFlow()

  val agentSupportedState = MutableStateFlow(true)
  val agentSupported: StateFlow<Boolean> = agentSupportedState.asStateFlow()

  val busyState = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = busyState.asStateFlow()

  val stoppableState = MutableStateFlow(false)
  val stoppable: StateFlow<Boolean> = stoppableState.asStateFlow()

  @Volatile
  var dropUnprompted = false

  val queuedState = MutableStateFlow<List<QueuedChatMessage>>(emptyList())
  val queued: StateFlow<List<QueuedChatMessage>> = queuedState.asStateFlow()

  private data class DeliveryClaim(val id: String, val entries: List<QueuedChatMessage>, var uncertain: Boolean = false)
  private val queueLock = Any()
  private var deliveryClaim: DeliveryClaim? = null
  private val deliveryState = MutableStateFlow<ChatDeliveryView?>(null)
  val delivery: StateFlow<ChatDeliveryView?> = deliveryState.asStateFlow()

  data class ClaimedDelivery internal constructor(val deliveryId: String, val entries: List<QueuedChatMessage>)

  sealed interface DeliveryTransition {
    data object Accepted : DeliveryTransition
    data class Restored(val reason: String) : DeliveryTransition
    data class RetainedUncertain(val reason: String) : DeliveryTransition
    data object Ignored : DeliveryTransition
  }

  fun enqueue(message: QueuedChatMessage) = synchronized(queueLock) {
    queuedState.value = queuedState.value + message
  }

  fun claimDelivery(deliveryId: String): ClaimedDelivery? = synchronized(queueLock) {
    if (deliveryClaim != null || queuedState.value.isEmpty()) return@synchronized null
    val entries = queuedState.value
    queuedState.value = emptyList()
    deliveryClaim = DeliveryClaim(deliveryId, entries)
    deliveryState.value = ChatDeliveryView(deliveryId,
      entries.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"), entries.flatMap { it.images })
    ClaimedDelivery(deliveryId, entries)
  }

  fun hasDeliveryClaim(): Boolean = synchronized(queueLock) { deliveryClaim != null }

  fun reduceDelivery(result: ChatSteeringResult): DeliveryTransition = synchronized(queueLock) {
    val claim = deliveryClaim?.takeIf { it.id == result.deliveryId } ?: return@synchronized DeliveryTransition.Ignored
    when (result) {
      is ChatSteeringResult.Accepted -> {
        deliveryClaim = null
        deliveryState.value = null
        DeliveryTransition.Accepted
      }
      is ChatSteeringResult.DeliveryUncertain -> {
        if (claim.uncertain) DeliveryTransition.Ignored
        else {
          claim.uncertain = true
          deliveryState.value = deliveryState.value?.copy(status = ChatDeliveryStatus.Uncertain, reason = result.reason)
          DeliveryTransition.RetainedUncertain(result.reason)
        }
      }
      is ChatSteeringResult.Rejected -> {
        queuedState.value = claim.entries + queuedState.value
        deliveryClaim = null
        deliveryState.value = null
        DeliveryTransition.Restored(result.reason)
      }
      is ChatSteeringResult.Unsupported, is ChatSteeringResult.NoActiveTurn -> {
        if (claim.uncertain) return@synchronized DeliveryTransition.Ignored
        queuedState.value = claim.entries + queuedState.value
        deliveryClaim = null
        deliveryState.value = null
        val reason = when (result) {
          is ChatSteeringResult.Unsupported -> result.reason
          is ChatSteeringResult.NoActiveTurn -> result.reason
          else -> error("Expected a known-unsent result")
        }
        DeliveryTransition.Restored(reason)
      }
    }
  }

  fun deferDestructiveAction(action: String): Boolean = synchronized(queueLock) {
    if (deliveryClaim == null) return@synchronized false
    deliveryState.value = deliveryState.value?.copy(blockedAction = action)
    true
  }

  fun keepOpen() = synchronized(queueLock) {
    deliveryState.value = deliveryState.value?.copy(blockedAction = null)
  }

  fun releaseDelivery(deliveryId: String, retry: Boolean): Boolean = synchronized(queueLock) {
    val claim = deliveryClaim?.takeIf { it.id == deliveryId } ?: return@synchronized false
    if (retry && !claim.uncertain) return@synchronized false
    if (retry) queuedState.value = claim.entries + queuedState.value
    deliveryClaim = null
    deliveryState.value = null
    true
  }

  fun takeOrdinaryQueuedEntries(): List<QueuedChatMessage> = synchronized(queueLock) {
    queuedState.value.also { queuedState.value = emptyList() }
  }

  fun hasQueuedMessages(): Boolean = synchronized(queueLock) { queuedState.value.isNotEmpty() }

  fun takeQueuedEntries(): List<QueuedChatMessage> = synchronized(queueLock) {
    if (deliveryClaim != null) return@synchronized emptyList()
    queuedState.value.also { queuedState.value = emptyList() }
  }

  fun clearQueuedEntries(): List<QueuedChatMessage> = synchronized(queueLock) {
    queuedState.value.also { queuedState.value = emptyList() }
  }

  fun replaceQueuedEntries(entries: List<QueuedChatMessage>) = synchronized(queueLock) {
    queuedState.value = entries
  }

  fun takeQueuedMessage(): QueuedChatMessage? {
    val pending = takeQueuedEntries()
    val first = pending.firstOrNull() ?: return null
    return first.copy(
      text = pending.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"),
      action = pending.singleOrNull()?.action,
      pauseAndAct = pending.any { it.pauseAndAct },
      images = pending.flatMap { it.images },
      cursorMcpDependency = pending.map { it.cursorMcpDependency }.distinct().singleOrNull() ?: CursorMcpDependency.Unspecified,
      onRejected = pending.mapNotNull { it.onRejected }.takeIf { it.isNotEmpty() }?.let { callbacks ->
        { callbacks.forEach { callback -> callback() } }
      },
    )
  }

  fun append(message: ChatMessage) = messages.update { it + message }

  fun showCapability(agent: ChatAgent) {
    val reason = agent.approvalUnavailableReason ?: return
    messages.update { transcript ->
      if (transcript.any { it is ChatMessage.Error && it.failure == ChatFailure.ApprovalUnsupported && it.text == reason }) {
        transcript
      } else {
        transcript + ChatMessage.Error(ChatFailure.ApprovalUnsupported, reason)
      }
    }
  }

  fun replaceTranscript(transcript: List<ChatMessage>) {
    messages.value = transcript
  }

  /**
   * The latest [limit] operator messages and finished agent replies, as a resumed chat shows them again. Images are never
   * saved; an image-only message keeps its `[Image #N]` labels as text so it neither restores as an empty bubble nor
   * leaves the saved chat untitled.
   */
  fun historyLines(limit: Int = SAVED_LINES): List<ChatHistoryLine> {
    val lines = messages.value.mapNotNull { message ->
      when {
        message is ChatMessage.Notice && message.operationCard != null ->
          ChatHistoryLine(ChatHistoryRole.OperationCard, message.text, operationCard = message.operationCard)
        message is ChatMessage.Notice -> ChatHistoryLine(ChatHistoryRole.Notice, message.text)
        message is ChatMessage.Error -> ChatHistoryLine(ChatHistoryRole.Error, message.text, failure = message.failure)
        message is ChatMessage.Operator -> ChatHistoryLine(ChatHistoryRole.Operator, message.savedText(), selectedAction = message.action)
        message is ChatMessage.Agent && !message.streaming && (message.text.isNotBlank() || message.actions.isNotEmpty()) ->
          ChatHistoryLine(ChatHistoryRole.Agent, message.text, actions = message.actions)
        message is ChatMessage.Approval -> message.card.savedApprovalLine()
        message is ChatMessage.OperationCard -> message.savedOperationCardLine()
        else -> null
      }
    }
    // The limit clips conversation prose only: every approval request, operation card and outcome of the chat is kept, in order.
    val keptProse = lines.withIndex().filter { !it.value.role.isCard }.takeLast(limit).map { it.index }.toSet()
    return lines.filterIndexed { index, line -> line.role.isCard || index in keptProse }
  }

  /**
   * Shows [card] in place of the earlier card of the same launch, or appends it. A card that settled never goes back to
   * awaiting, so a late read of an older state cannot re-open a decision.
   */
  fun showOperationCard(card: OperationCardView, restoredCopy: Boolean = false) = messages.update { list ->
    val index = list.indexOfFirst { it is ChatMessage.OperationCard && it.card.launchId == card.launchId }
    if (index < 0) {
      val placeholder = list.indexOfFirst {
        (it as? ChatMessage.Notice)?.let { notice ->
          notice.operationCard?.token == card.token || notice.text.startsWith(card.operationCardLabelPrefix())
        } == true
      }
      val restored = (list.getOrNull(placeholder) as? ChatMessage.Notice)?.operationCard
      val choices = restored?.draftChoices?.let { card.validateChoices(it) }.orEmpty()
      val shown = ChatMessage.OperationCard(
        card,
        restoredCopy = restoredCopy,
        refusal = restored?.refusal,
        choices = choices,
        choicesSaved = restored?.draftChoices != null,
      )
      return@update if (placeholder < 0) list + shown else list.toMutableList().also { it[placeholder] = shown }
    }
    val existing = list[index] as ChatMessage.OperationCard
    if (existing.card.token != card.token || existing.card.launchId != card.launchId) return@update list
    if (existing.card.state.terminal && !card.state.terminal) return@update list
    list.toMutableList().also {
      it[index] = existing.copy(
        card = card,
        restoredCopy = restoredCopy,
        submitting = existing.submitting,
        refusal = card.evidence ?: existing.refusal,
        choices = existing.choices,
      )
    }
  }

  /** The card of [token] this chat shows, if any. */
  fun operationCard(token: String): ChatMessage.OperationCard? =
    messages.value.firstNotNullOfOrNull { (it as? ChatMessage.OperationCard)?.takeIf { message -> message.card.token == token } }

  /** Records the offered [optionId] the operator picked for thread [ordinal] of the card of [token]. */
  fun chooseOperationOption(token: String, ordinal: String, optionId: String) = updateOperationChoices(token) { it + (ordinal to optionId) }

  /** Records the option each thread of the card of [token] marks as recommended; a thread without one stays unchosen. */
  fun chooseRecommendedOptions(token: String) = updateOperationChoices(token) { chosen ->
    val details = operationCard(token)?.card?.details as? OperationCardDetailsView.PrReviewFix ?: return@updateOperationChoices chosen
    chosen + details.threads.mapNotNull { thread -> thread.options.firstOrNull { it.recommended }?.let { thread.ordinal to it.optionId } }
  }

  private fun updateOperationChoices(token: String, change: (Map<String, String>) -> Map<String, String>) = messages.update { list ->
    list.map {
      if (it is ChatMessage.OperationCard && it.card.token == token && !it.submitting && it.card.state.decidable && it.card.canConfirm) {
        it.copy(choices = change(it.choices), choicesSaved = true, refusal = null)
      } else {
        it
      }
    }
  }

  /** Claims one live awaiting-card action atomically; confirmation additionally requires current confirmation eligibility. */
  fun beginOperationDecision(token: String, decision: OperationCardDecision): ChatMessage.OperationCard? {
    var begun: ChatMessage.OperationCard? = null
    messages.update { list ->
      begun = null
      list.map {
        if (
          it is ChatMessage.OperationCard && it.card.token == token && !it.submitting && !it.restoredCopy &&
            it.card.state.decidable && (decision != OperationCardDecision.Confirm || it.card.canConfirm)
        ) {
          it.copy(submitting = true, refusal = null).also { claimed -> begun = claimed }
        } else {
          it
        }
      }
    }
    return begun
  }

  fun operationCardSubmitting(token: String, submitting: Boolean, refusal: String? = null) = messages.update { list ->
    list.map {
      if (it is ChatMessage.OperationCard && it.card.token == token) it.copy(submitting = submitting, refusal = refusal) else it
    }
  }

  /**
   * Shows [card] in place of the earlier card of the same request, or appends it. A request never goes back from a
   * settled outcome to pending, and a live card keeps the grant eligibility the feed already supplied.
   */
  fun showApproval(card: ApprovalCard, live: Boolean = true) = messages.update { list ->
    val index = list.indexOfFirst { it is ChatMessage.Approval && it.card.key == card.key }
    if (index < 0) return@update list + ChatMessage.Approval(card, restoredCopy = !live)
    val existing = list[index] as ChatMessage.Approval
    if (!existing.card.isPending && card.isPending) return@update list
    val merged = if (card.isPending && card.grants == null) card.copy(grants = existing.card.grants) else card
    val settled = !merged.isPending
    list.toMutableList().also {
      it[index] = existing.copy(
        card = merged,
        restoredCopy = existing.restoredCopy && !live,
        submitting = existing.submitting && !settled,
        refusal = existing.refusal.takeUnless { settled },
      )
    }
  }

  /** The card currently shown for [key], if the transcript has one. */
  fun approval(key: ApprovalCardKey): ApprovalCard? =
    messages.value.firstNotNullOfOrNull { (it as? ChatMessage.Approval)?.card?.takeIf { card -> card.key == key } }

  /**
   * Claims the one click on a live pending card: returns its card and marks it submitting, or null when it is already
   * submitting, settled or only a restored copy. This is what keeps a double click from sending two decisions.
   */
  fun beginApprovalDecision(key: ApprovalCardKey): ApprovalCard? {
    var begun: ApprovalCard? = null
    messages.update { list ->
      begun = null
      list.map {
        if (it is ChatMessage.Approval && it.card.key == key && !it.submitting && !it.restoredCopy && it.card.isPending) {
          begun = it.card
          it.copy(submitting = true, refusal = null)
        } else {
          it
        }
      }
    }
    return begun
  }

  /** Marks the card of [key] as awaiting the answer to a click sent from this window. */
  fun approvalSubmitting(key: ApprovalCardKey, submitting: Boolean, refusal: String? = null) = messages.update { list ->
    list.map {
      if (it is ChatMessage.Approval && it.card.key == key) it.copy(submitting = submitting, refusal = refusal) else it
    }
  }

  fun appendDelta(text: String) = updateStreaming { it.copy(text = it.text + text) }

  fun appendTool(call: ChatToolCall) = updateStreaming { message ->
    val exists = message.tools.any { it.call.id == call.id }
    message.copy(tools = if (exists) message.tools.map { if (it.call.id == call.id) it.copy(call = call) else it }
      else message.tools + ChatToolEntry(call, startedAt = clock.now()))
  }

  fun recordToolResult(result: ChatEvent.ToolResult) = messages.update { list ->
    list.map { message ->
      if (message is ChatMessage.Agent) message.copy(tools = message.tools.map {
        if (it.call.id == result.callId) it.copy(result = result, finishedAt = clock.now()) else it
      }) else message
    }
  }

  fun suggestActions(actions: List<ChatSuggestedAction>) = updateStreaming { it.copy(actions = actions) }

  // A card splits a reply, and a turn can be cut off right after one, so every streaming reply ends, not only the last message.
  fun endStreaming() = messages.update { list ->
    if (list.none { it is ChatMessage.Agent && it.streaming }) {
      list
    } else {
      list.map { if (it is ChatMessage.Agent && it.streaming) it.copy(streaming = false, finishedAt = clock.now()) else it }
    }
  }

  /** A new provider message keeps tool-only output in the same transcript entry. */
  fun endMessage() = messages.update { list ->
    val last = list.lastOrNull()
    if (last is ChatMessage.Agent && last.streaming && (last.text.isNotEmpty() || last.actions.isNotEmpty())) {
      list.dropLast(1) + last.copy(streaming = false, finishedAt = clock.now())
    } else {
      list
    }
  }

  // A card stays where it was requested: output after it finishes the reply above and continues in a new reply below it.
  private fun updateStreaming(change: (ChatMessage.Agent) -> ChatMessage.Agent) = messages.update { list ->
    val last = list.lastOrNull()
    if (last is ChatMessage.Agent && last.streaming) {
      list.toMutableList().also { it[it.lastIndex] = change(last) }
    } else {
      val now = clock.now()
      val settled = if (last?.isCard == true) {
        list.map { if (it is ChatMessage.Agent && it.streaming) it.copy(streaming = false, finishedAt = now) else it }
      } else {
        list
      }
      settled + change(ChatMessage.Agent("", streaming = true, tools = emptyList(), startedAt = now))
    }
  }
}

internal fun ChatThread.show(agent: ChatAgent?, event: ChatEvent) {
  when (event) {
    ChatEvent.MessageStarted -> endMessage()
    is ChatEvent.TextDelta -> appendDelta(event.text)
    is ChatEvent.Notice -> append(ChatMessage.Notice(event.text))
    is ChatEvent.ToolUse -> appendTool(event.call)
    is ChatEvent.ToolResult -> recordToolResult(event)
    is ChatEvent.SuggestedActions -> suggestActions(event.actions)
    is ChatEvent.ApprovalRequested -> showApproval(event.card)
    ChatEvent.Completed -> endStreaming()
    is ChatEvent.Failed -> {
      endStreaming()
      append(
        if (event.failure == ChatFailure.AgentUnsupported) {
          ChatMessage.Notice("Chat is not available for agent ${event.detail ?: agent?.agentId} yet.")
        } else {
          ChatMessage.Error(event.failure, event.detail ?: event.failure.name)
        },
      )
    }
  }
}

internal fun ChatMessage.Operator.savedText(): String =
  text.ifBlank { images.indices.joinToString(" ") { imageLabel(it + 1) } }

/** The message a saved line shows as when a chat resumes. */
internal fun restored(line: ChatHistoryLine): ChatMessage = when (line.role) {
  ChatHistoryRole.Operator -> ChatMessage.Operator(line.text, line.selectedAction)
  ChatHistoryRole.Agent -> ChatMessage.Agent(line.text, streaming = false, tools = emptyList(), actions = line.actions)
  ChatHistoryRole.Notice -> ChatMessage.Notice(line.text, confirmation = line.text.endsWith(START_AGAIN_SUFFIX))
  ChatHistoryRole.Error -> ChatMessage.Error(line.failure, line.text)
  // A saved card with no record cannot be shown as a card, so it falls back to its one-line label.
  ChatHistoryRole.Approval -> line.approval?.let { ChatMessage.Approval(it, restoredCopy = true) } ?: ChatMessage.Notice(line.text)
  // An operation card keeps only its identity; it shows its label until the store answers the revalidation read.
  ChatHistoryRole.OperationCard -> ChatMessage.Notice(line.text, operationCard = line.operationCard)
}

/** A card message sits beside the reply it follows instead of ending it. */
internal val ChatMessage.isCard: Boolean get() = this is ChatMessage.Approval || this is ChatMessage.OperationCard

internal val ChatHistoryRole.isCard: Boolean get() = this == ChatHistoryRole.Approval || this == ChatHistoryRole.OperationCard

/** The start of a saved card's label; a restored card shows the label until the store answers, then is replaced in place. */
internal fun OperationCardView.operationCardLabelPrefix(): String = "$operationId approval $token:"

/** The saved form of an operation card: its identity and a one-line label; the card itself is read from the store again. */
internal fun ChatMessage.OperationCard.savedOperationCardLine(): ChatHistoryLine =
  ChatHistoryLine(
    ChatHistoryRole.OperationCard,
    "${card.operationCardLabelPrefix()} ${card.state.name}",
    operationCard = OperationCardReference(
      card.token,
      card.launchId,
      card.operationId,
      card.state,
      choices.takeIf { choicesSaved },
      refusal,
    ),
  )

private fun OperationCardView.validateChoices(choices: Map<String, String>): Map<String, String> {
  val details = details as? OperationCardDetailsView.PrReviewFix ?: return emptyMap()
  return choices.filter { (ordinal, optionId) ->
    details.threads.any { thread -> thread.ordinal == ordinal && thread.options.any { it.optionId == optionId } }
  }
}

/** The saved form of a card: the complete record, without the runtime's grant answer, which is never stored. */
internal fun ApprovalCard.savedApprovalLine(): ChatHistoryLine =
  ChatHistoryLine(ChatHistoryRole.Approval, "${title}: ${state}", approval = copy(grants = null))
