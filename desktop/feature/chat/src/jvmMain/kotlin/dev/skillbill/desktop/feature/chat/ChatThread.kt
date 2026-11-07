package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

private const val SAVED_LINES = 10

internal const val STOPPED_NOTICE = "Stopped"

internal const val START_AGAIN_SUFFIX = "Send another message to start again."

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

  fun takeQueuedMessage(): QueuedChatMessage? {
    val pending = queuedState.getAndUpdate { emptyList() }
    val first = pending.firstOrNull() ?: return null
    return first.copy(
      text = pending.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"),
      action = pending.singleOrNull()?.action,
      pauseAndAct = pending.any { it.pauseAndAct },
      images = pending.flatMap { it.images },
    )
  }

  fun append(message: ChatMessage) = messages.update { it + message }

  fun replaceTranscript(transcript: List<ChatMessage>) {
    messages.value = transcript
  }

  /**
   * The latest [limit] operator messages and finished agent replies, as a resumed chat shows them again. Images are never
   * saved; an image-only message keeps its `[Image #N]` labels as text so it neither restores as an empty bubble nor
   * leaves the saved chat untitled.
   */
  fun historyLines(limit: Int = SAVED_LINES): List<ChatHistoryLine> = messages.value.mapNotNull { message ->
    when {
      message is ChatMessage.Operator -> ChatHistoryLine(ChatHistoryRole.Operator, message.savedText(), selectedAction = message.action)
      message is ChatMessage.Agent && !message.streaming && (message.text.isNotBlank() || message.actions.isNotEmpty()) ->
        ChatHistoryLine(ChatHistoryRole.Agent, message.text, actions = message.actions)
      else -> null
    }
  }.takeLast(limit)

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

  fun endStreaming() = messages.update { list ->
    val last = list.lastOrNull()
    if (last is ChatMessage.Agent && last.streaming) list.dropLast(1) + last.copy(streaming = false, finishedAt = clock.now()) else list
  }

  private fun updateStreaming(change: (ChatMessage.Agent) -> ChatMessage.Agent) = messages.update { list ->
    val last = list.lastOrNull()
    if (last is ChatMessage.Agent && last.streaming) {
      list.dropLast(1) + change(last)
    } else {
      list + change(ChatMessage.Agent("", streaming = true, tools = emptyList(), startedAt = clock.now()))
    }
  }
}

internal fun ChatThread.show(agent: ChatAgent?, event: ChatEvent) {
  when (event) {
    is ChatEvent.TextDelta -> appendDelta(event.text)
    is ChatEvent.ToolUse -> appendTool(event.call)
    is ChatEvent.ToolResult -> recordToolResult(event)
    is ChatEvent.SuggestedActions -> suggestActions(event.actions)
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
}
