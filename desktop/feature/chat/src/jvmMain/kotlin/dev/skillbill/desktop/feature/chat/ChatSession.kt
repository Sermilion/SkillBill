package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
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

/**
 * One execution's chat. [sessionId] names the agent CLI session; [job] parents every turn, so cancelling it ends the turn's
 * process. [delivered] is the snapshot the agent last received and the base of the next diff.
 */
class ChatSession internal constructor(
  initial: WorkItem,
  val sessionId: String,
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

  internal var delivered: ExecutionSnapshot? = null
  internal var started: Boolean = false

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

  internal fun append(message: ChatMessage) = messages.update { it + message }

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
