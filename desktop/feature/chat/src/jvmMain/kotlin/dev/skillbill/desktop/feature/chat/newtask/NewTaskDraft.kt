package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.feedback.readProblemText
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

data class NewTaskOffer(val starting: Boolean = false, val prefixKind: NewTaskKind? = null, val choices: List<String> = emptyList(), val problem: String? = null)

/** What the operator set up on New task, with its chat. It lives with the window, so navigation and session switches keep it. */
@Inject
@SingleIn(WindowScope::class)
class NewTaskDraft(
  private val chats: ExecutionChatRepository,
  private val workControls: WorkControlRepository,
  private val databaseSession: DatabaseSessionController,
  private val toasts: ToastQueue,
  windowScope: WindowCoroutineScope,
  private val clock: Clock,
) {
  private val scope = windowScope.scope
  private val chatState = MutableStateFlow<NewTaskChat?>(null)
  private val savedState = MutableStateFlow<List<ChatHistoryEntry>>(emptyList())
  private val offerState = MutableStateFlow(NewTaskOffer())
  private var savedRead: Job? = null
  private var closing = false
  internal var chatDispatcher = Dispatchers.IO

  var text: String by mutableStateOf("")
  var repository: NewTaskRepository? by mutableStateOf(null)
  var baseBranch: String by mutableStateOf("")
  var agentId: String? by mutableStateOf(null)
  var selectedAgent: ChatAgent? by mutableStateOf(null)

  internal val chat: StateFlow<NewTaskChat?> = chatState.asStateFlow()

  internal val offer: StateFlow<NewTaskOffer> = offerState.asStateFlow()

  /** The selected repository's saved drafts, newest first, as of the latest [refreshSaved]. */
  internal val saved: StateFlow<List<ChatHistoryEntry>> = savedState.asStateFlow()

  internal fun canSend(): Boolean = canAdmitTurn(text)

  private fun canAdmitTurn(message: String): Boolean {
    val current = chatState.value
    return !closing && message.isNotBlank() && repository != null && selectedAgent != null && selectedAgent?.agentId == agentId &&
      current?.thread?.busy?.value != true && current?.repositoryMissing != true && !offerState.value.starting
  }

  /** Sends the typed text, opening the chat on the first message, and clears it. It does nothing unless [canSend] holds. */
  internal fun send() {
    if (!canSend()) return
    sendTurn(text, null)
  }

  internal fun sendSuggestedAction(action: ChatSuggestedAction): Boolean {
    if (!canAdmitTurn(action.instruction) || chatState.value == null) return false
    sendTurn(action.instruction, action)
    return true
  }

  private fun sendTurn(submitted: String, action: ChatSuggestedAction?) {
    val current = chatState.value ?: open(UUID.randomUUID().toString(), checkNotNull(repository).root, baseBranch.ifBlank { null }, checkNotNull(selectedAgent))
    current.send(submitted, action, onAccepted = { if (action == null && text == submitted) text = "" })
  }

  internal fun start(kind: NewTaskKind, keyChoice: String? = null, target: String? = null) {
    val current = chatState.value
    if (closing || offerState.value.starting || current?.thread?.busy?.value == true) return
    if (current == null && kind in setOf(NewTaskKind.GoalRun, NewTaskKind.PlanOnly)) return
    val agent = selectedAgent ?: return
    val root = repository?.root ?: return
    val requirements = current?.let { newTaskRequirements(it.thread.transcript.value) }.orEmpty()
    val textAtStart = text
    val expectedSessionId = current?.databaseSessionId ?: if (current == null) {
      (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId
    } else null
    val startRequest = NewTaskStart(root, requirements, baseBranch.ifBlank { null }, agent.agentId, kind, keyChoice, expectedSessionId, target)
    offerState.value = offerState.value.copy(starting = true, problem = null)
    scope.launch {
      try {
        val outcome = workControls.startNewTask(startRequest)
        when (outcome) {
          is NewTaskStartOutcome.Started -> {
            val message = when (outcome.kind) {
              NewTaskKind.GoalRun -> "Started goal run ${outcome.issueKey}"
              NewTaskKind.PlanOnly -> "Started plan ${outcome.issueKey}"
              else -> "Started ${outcome.kind.name.lowercase()}"
            }
            toasts.show(ToastMessage(ToastTone.Confirmation, message))
            if (kind in setOf(NewTaskKind.GoalRun, NewTaskKind.PlanOnly) && current != null && chatState.value === current) {
              if (text == textAtStart) text = ""
              discard()
            }
          }
          is NewTaskStartOutcome.NeedsPrefix -> {
            if (ownsChat(current)) {
              offerState.value = offerState.value.copy(prefixKind = kind, choices = outcome.choices, problem = null)
            } else {
              toasts.show(ToastMessage(ToastTone.Problem, "Choose an issue prefix to start this run."))
            }
          }
          is NewTaskStartOutcome.Refused -> {
            if (outcome.message == "The database changed. Try again." && current != null && ownsChat(current)) {
              current.databaseSessionId = (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId
            }
            showStartProblem(current, outcome.message)
          }
          is NewTaskStartOutcome.Failed -> showStartProblem(current, readProblemText(outcome.problem))
        }
      } finally {
        offerState.value = offerState.value.copy(starting = false)
      }
    }
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
    val resumed = open(
      entry.sessionId,
      context.repositoryRoot,
      context.baseBranch,
      entry.agent,
      context.sessionEstablished,
      entry.lines.any { it.role == ChatHistoryRole.Operator } && context.pendingTurnLineIndex == null,
      context.pendingTurnLineIndex,
      missing,
    )
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
    clearChatFeedback()
  }

  private fun open(
    sessionId: String,
    root: String,
    branch: String?,
    agent: ChatAgent,
    started: Boolean = false,
    firstTurnEnded: Boolean = started,
    pendingTurnLineIndex: Int? = null,
    missing: Boolean = false,
  ): NewTaskChat =
    NewTaskChat(
      chats,
      scope,
      clock,
      sessionId,
      (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId,
      root,
      branch,
      agent,
      started,
      firstTurnEnded,
      pendingTurnLineIndex,
      missing,
      chatDispatcher,
    ).also {
      clearChatFeedback()
      chatState.value = it
    }

  // Waits for the cancelled turn first, so a save it had in flight cannot recreate a deleted draft or miss the refreshed list.
  private fun leave(current: NewTaskChat, afterEnd: suspend () -> Unit) {
    current.end()
    if (chatState.value === current) {
      chatState.value = null
      clearChatFeedback()
    }
    scope.launch {
      current.job.join()
      afterEnd()
      refreshSaved()
    }
  }

  private fun ownsChat(current: NewTaskChat?): Boolean = current != null && chatState.value === current

  private fun showStartProblem(current: NewTaskChat?, message: String) {
    if (ownsChat(current)) {
      offerState.value = offerState.value.copy(problem = message)
    } else {
      toasts.show(ToastMessage(ToastTone.Problem, message))
    }
  }

  private fun clearChatFeedback() {
    offerState.value = offerState.value.copy(prefixKind = null, choices = emptyList(), problem = null)
  }
}
