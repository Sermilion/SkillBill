package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

internal const val SAVED_LINES = 10

/**
 * One execution's chat. [sessionId] names the agent CLI session and changes when the operator switches agents; [job] parents
 * every turn, so cancelling it ends the turn's process; [turnJob] is the turn that owns [busy] now. [delivered] is the snapshot
 * the agent last received and the base of the next diff.
 */
class ChatSession internal constructor(
  initial: WorkItem,
  sessionId: String,
  internal val job: Job,
  private val clock: Clock = Clock.System,
) {
  private val headerState = MutableStateFlow(initial)

  /** The execution as of the latest read; it starts as the row the chat opened from. */
  val header: StateFlow<WorkItem> = headerState.asStateFlow()

  internal var item: WorkItem
    get() = headerState.value
    set(value) {
      headerState.value = value
    }

  var sessionId: String = sessionId
    private set

  internal var delivered: ExecutionSnapshot? = null
  internal var started: Boolean = false
  internal var turnJob: Job? = null

  /** The next turn opens [newSessionId] and sends the full snapshot, as a first turn does. */
  internal fun restart(newSessionId: String) {
    sessionId = newSessionId
    delivered = null
    started = false
  }

  /** The next turn continues the saved agent session [savedSessionId] and sends the full snapshot, since what it last saw is unknown. */
  internal fun resumeFrom(savedSessionId: String) {
    sessionId = savedSessionId
    delivered = null
    started = true
  }

  private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val transcript: StateFlow<List<ChatMessage>> = messages.asStateFlow()

  internal val agentState = MutableStateFlow<ChatAgent?>(null)
  val agent: StateFlow<ChatAgent?> = agentState.asStateFlow()

  internal val agentSupportedState = MutableStateFlow(true)
  val agentSupported: StateFlow<Boolean> = agentSupportedState.asStateFlow()

  internal val busyState = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = busyState.asStateFlow()

  internal val modeState = MutableStateFlow(modeFor(item))
  val mode: StateFlow<ChatPermissionMode> = modeState.asStateFlow()

  internal val goneState = MutableStateFlow(false)
  val gone: StateFlow<Boolean> = goneState.asStateFlow()

  /** The saved chats `/resume` offers; null while the picker is closed. */
  internal val resumeChoicesState = MutableStateFlow<List<ChatHistoryEntry>?>(null)
  val resumeChoices: StateFlow<List<ChatHistoryEntry>?> = resumeChoicesState.asStateFlow()

  internal fun append(message: ChatMessage) = messages.update { it + message }

  internal fun replaceTranscript(transcript: List<ChatMessage>) {
    messages.value = transcript
  }

  /** The latest operator messages and finished agent replies with text, as a resumed chat shows them again. */
  internal fun historyLines(): List<ChatHistoryLine> = messages.value.mapNotNull { message ->
    when {
      message is ChatMessage.Operator -> ChatHistoryLine(ChatHistoryRole.Operator, message.text)
      message is ChatMessage.Agent && !message.streaming && message.text.isNotBlank() -> ChatHistoryLine(ChatHistoryRole.Agent, message.text)
      else -> null
    }
  }.takeLast(SAVED_LINES)

  internal fun appendDelta(text: String) = updateStreaming { it.copy(text = it.text + text) }

  internal fun appendTool(call: ChatToolCall) = updateStreaming { message ->
    val exists = message.tools.any { it.call.id == call.id }
    message.copy(tools = if (exists) message.tools.map { if (it.call.id == call.id) it.copy(call = call) else it }
      else message.tools + ChatToolEntry(call, startedAt = clock.now()))
  }

  internal fun recordToolResult(result: ChatEvent.ToolResult) = messages.update { list ->
    list.map { message ->
      if (message is ChatMessage.Agent) message.copy(tools = message.tools.map {
        if (it.call.id == result.callId) it.copy(result = result, finishedAt = clock.now()) else it
      }) else message
    }
  }

  internal fun suggestActions(actions: List<ChatSuggestedAction>) = updateStreaming { it.copy(actions = actions) }

  internal fun endStreaming() = messages.update { list ->
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
