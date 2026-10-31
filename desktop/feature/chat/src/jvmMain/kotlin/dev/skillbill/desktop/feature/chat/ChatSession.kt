package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One execution's chat. [sessionId] names the agent CLI session; [job] parents every turn, so cancelling it ends the turn's
 * process. [delivered] is the snapshot the agent last received and the base of the next diff.
 */
class ChatSession internal constructor(internal var item: WorkItem, val sessionId: String, internal val job: Job) {
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

  internal fun appendTool(name: String) = updateStreaming { it.copy(tools = it.tools + name) }

  internal fun endStreaming() = messages.update { list ->
    val last = list.lastOrNull()
    if (last is ChatMessage.Agent && last.streaming) list.dropLast(1) + last.copy(streaming = false) else list
  }

  private fun updateStreaming(change: (ChatMessage.Agent) -> ChatMessage.Agent) = messages.update { list ->
    val last = list.lastOrNull()
    if (last is ChatMessage.Agent && last.streaming) {
      list.dropLast(1) + change(last)
    } else {
      list + change(ChatMessage.Agent("", streaming = true, tools = emptyList()))
    }
  }
}
