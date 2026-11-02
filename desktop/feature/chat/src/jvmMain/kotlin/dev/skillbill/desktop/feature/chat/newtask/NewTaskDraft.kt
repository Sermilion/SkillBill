package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.restored
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.util.UUID
import kotlin.time.Clock

data class NewTaskRepository(val root: String, val name: String)

/** What the operator set up on New task, with its chat. It lives with the window, so navigation and session switches keep it. */
@Inject
@SingleIn(WindowScope::class)
class NewTaskDraft(
  private val chats: ExecutionChatRepository,
  windowScope: WindowCoroutineScope,
  private val clock: Clock,
) {
  private val scope = windowScope.scope
  private val chatState = MutableStateFlow<NewTaskChat?>(null)
  private val savedState = MutableStateFlow<List<ChatHistoryEntry>>(emptyList())
  private var savedRead: Job? = null
  private var closing = false
  internal var chatDispatcher = Dispatchers.IO

  var text: String by mutableStateOf("")
  var repository: NewTaskRepository? by mutableStateOf(null)
  var baseBranch: String by mutableStateOf("")
  var agentId: String? by mutableStateOf(null)
  var selectedAgent: ChatAgent? by mutableStateOf(null)

  internal val chat: StateFlow<NewTaskChat?> = chatState.asStateFlow()

  /** The selected repository's saved drafts, newest first, as of the latest [refreshSaved]. */
  internal val saved: StateFlow<List<ChatHistoryEntry>> = savedState.asStateFlow()

  internal fun canSend(): Boolean {
    val current = chatState.value
    return !closing && text.isNotBlank() && repository != null && selectedAgent != null && selectedAgent?.agentId == agentId &&
      current?.thread?.busy?.value != true && current?.repositoryMissing != true
  }

  /** Sends the typed text, opening the chat on the first message, and clears it. It does nothing unless [canSend] holds. */
  internal fun send() {
    if (!canSend()) return
    val current = chatState.value ?: open(UUID.randomUUID().toString(), checkNotNull(repository).root, baseBranch.ifBlank { null }, checkNotNull(selectedAgent))
    val submitted = text
    current.send(submitted, onAccepted = { if (text == submitted) text = "" })
  }

  /** Ends the chat and deletes its saved draft; the selections and any typed text stay. */
  internal fun discard() {
    if (closing) return
    val current = chatState.value ?: return
    leave(current) { chats.deleteSavedChat(current.sessionId) }
  }

  /** Writes any running reply, then ends the chat's agent process and keeps its saved draft, for "New chat" and a window close. */
  internal suspend fun close(): Boolean {
    if (closing) return false
    val current = chatState.value ?: return true
    closing = true
    return try {
      if (!current.flush()) false else {
        leave(current) {}
        true
      }
    } finally {
      closing = false
    }
  }

  internal fun launch(block: suspend () -> Unit) = scope.launch { block() }

  /** Reopens [entry] on its agent session, or with only Discard offered when its repository no longer exists. */
  internal suspend fun resume(entry: ChatHistoryEntry): Boolean {
    val context = entry.newTask ?: return false
    if (!close()) return false
    repository = NewTaskRepository(context.repositoryRoot, File(context.repositoryRoot).name)
    baseBranch = context.baseBranch.orEmpty()
    agentId = entry.agent.agentId
    selectedAgent = entry.agent
    val missing = !File(context.repositoryRoot).isDirectory
    val resumed = open(entry.sessionId, context.repositoryRoot, context.baseBranch, entry.agent, context.sessionEstablished, context.pendingTurnLineIndex, missing)
    val lines = entry.lines.map(::restored)
    val notice = ChatMessage.Notice("This draft's repository no longer exists: ${context.repositoryRoot}. Discard it to continue.")
    resumed.thread.replaceTranscript(if (missing) lines + notice else lines)
    return true
  }

  internal fun refreshSaved() {
    val root = repository?.root
    savedRead?.cancel()
    savedRead = scope.launch { savedState.value = root?.let { chats.savedNewTaskDrafts(it) }.orEmpty() }
  }

  internal fun dispose() {
    chatState.value?.end()
    chatState.value = null
  }

  private fun open(
    sessionId: String,
    root: String,
    branch: String?,
    agent: ChatAgent,
    started: Boolean = false,
    pendingTurnLineIndex: Int? = null,
    missing: Boolean = false,
  ): NewTaskChat =
    NewTaskChat(chats, scope, clock, sessionId, root, branch, agent, started, pendingTurnLineIndex, missing, chatDispatcher).also { chatState.value = it }

  // Waits for the cancelled turn first, so a save it had in flight cannot recreate a deleted draft or miss the refreshed list.
  private fun leave(current: NewTaskChat, afterEnd: suspend () -> Unit) {
    current.end()
    chatState.value = null
    scope.launch {
      current.job.join()
      afterEnd()
      refreshSaved()
    }
  }
}
