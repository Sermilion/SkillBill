package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.toNewTaskKind
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
import dev.skillbill.desktop.feature.chat.chipText
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

data class NewTaskOffer(
  val starting: Boolean = false,
  val prefixKind: NewTaskKind? = null,
  val choices: List<String> = emptyList(),
  val action: ChatSuggestedAction? = null,
  val problem: String? = null,
  val started: String? = null,
)

private val GOAL_KINDS = setOf(NewTaskKind.GoalRun, NewTaskKind.PlanOnly)

private fun NewTaskKind.startName(): String =
  NewTaskActionKind.entries.firstOrNull { it.toNewTaskKind() == this }?.wire ?: name.lowercase()

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
      current?.repositoryMissing != true && !offerState.value.starting
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
    offerState.value = offerState.value.copy(prefixKind = null, choices = emptyList(), action = null, problem = null, started = null)
    current.send(submitted, action, onAccepted = { if (action == null && text == submitted) text = "" })
  }

  internal fun start(kind: NewTaskKind, keyChoice: String? = null, target: String? = null, action: ChatSuggestedAction? = null) {
    val current = chatState.value
    if (closing || offerState.value.starting || current?.thread?.busy?.value == true) return
    if (current == null && kind in RUN_KINDS) return
    if (kind in RUN_KINDS && offerState.value.started != null) return
    if (selectedAgent == null || repository == null) return
    offerState.value = NewTaskOffer(starting = true, started = offerState.value.started)
    startRequest(kind, keyChoice, target, action, current)
  }

  internal fun retryPrefix(keyChoice: String) {
    val pending = offerState.value
    val kind = pending.prefixKind ?: return
    val current = chatState.value
    if (kind !in GOAL_KINDS || closing || pending.starting || current?.thread?.busy?.value == true) return
    if (current == null || pending.started != null) return
    if (selectedAgent == null || repository == null) return
    offerState.value = pending.copy(starting = true, problem = null)
    startRequest(kind, keyChoice, null, pending.action, current)
  }

  private fun startRequest(
    kind: NewTaskKind,
    keyChoice: String?,
    target: String?,
    action: ChatSuggestedAction?,
    current: NewTaskChat?,
  ) {
    val agent = checkNotNull(selectedAgent)
    val root = checkNotNull(repository).root
    val transcript = current?.thread?.transcript?.value.orEmpty()
    val requirements = if (kind in GOAL_KINDS && action != null) action.instruction else {
      listOfNotNull(action?.instruction, newTaskRequirements(transcript))
        .filter(String::isNotBlank)
        .joinToString("\n\n")
    }
    val title = if (kind in GOAL_KINDS && action != null) action.title else action?.title ?: current?.let { newTaskTitle(transcript) }
    val expectedSessionId = current?.databaseSessionId ?: if (current == null) {
      (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId
    } else null
    val startRequest = NewTaskStart(
      root,
      requirements,
      baseBranch.ifBlank { null },
      agent.agentId,
      kind,
      keyChoice,
      expectedSessionId,
      title = title,
      target = target ?: action?.target,
    )
    scope.launch {
      try {
        val outcome = workControls.startNewTask(startRequest)
        when (outcome) {
          is NewTaskStartOutcome.Started -> {
            if (kind in GOAL_KINDS) {
              val message = when (outcome.kind) {
                NewTaskKind.GoalRun -> "Started goal run ${outcome.issueKey}"
                NewTaskKind.PlanOnly -> "Started plan ${outcome.issueKey}"
                else -> "Started ${outcome.kind.name.lowercase()}"
              }
              toasts.show(ToastMessage(ToastTone.Confirmation, message))
              if (kind in RUN_KINDS && ownsChat(current)) {
                offerState.value = NewTaskOffer(started = message)
              }
            } else {
              reportStandalone(current, kind, outcome)
            }
          }
          is NewTaskStartOutcome.NeedsKey -> {
            if (ownsChat(current)) {
              offerState.value = NewTaskOffer(starting = true, prefixKind = kind, choices = outcome.choices, action = action)
            } else {
              toasts.show(ToastMessage(ToastTone.Problem, "Choose an issue key to start this run."))
            }
          }
          is NewTaskStartOutcome.Refused -> {
            if (outcome.message == "The database changed. Try again." && current != null && ownsChat(current)) {
              current.databaseSessionId = (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId
            }
            showStartProblem(current, kind, outcome.message)
          }
          is NewTaskStartOutcome.Failed -> showStartProblem(current, kind, readProblemText(outcome.problem))
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

  /** Opens a new agent session on the current repository, branch and agent, keeping the transcript. */
  internal fun rebindChat() {
    val current = chatState.value ?: return
    val agent = selectedAgent ?: return
    val repo = repository ?: return
    if (closing || current.repositoryMissing || current.thread.busy.value || offerState.value.starting) return
    val branch = baseBranch.ifBlank { null }
    if (current.repositoryRoot == repo.root && current.baseBranch == branch && current.agent.agentId == agent.agentId) return
    val lines = current.thread.transcript.value
    val firstEnded = current.firstTurnEnded.value
    val previousId = current.sessionId
    current.end()
    val next = open(UUID.randomUUID().toString(), repo.root, branch, agent, firstTurnEnded = firstEnded)
    next.thread.replaceTranscript(lines + ChatMessage.Notice("Switched to ${chipText(agent)} in ${repo.name}."))
    scope.launch {
      current.job.join()
      chats.deleteSavedChat(previousId)
      if (!next.persist() && chatState.value === next) {
        next.thread.append(ChatMessage.Notice("This draft could not be saved. Keep it open and try again."))
      }
      refreshSaved()
    }
  }

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

  // A goal or plan problem belongs to the offer card; any other start reports in the chat that offered it.
  private fun showStartProblem(current: NewTaskChat?, kind: NewTaskKind, message: String) {
    when {
      !ownsChat(current) -> toasts.show(ToastMessage(ToastTone.Problem, message))
      kind in GOAL_KINDS -> offerState.value = offerState.value.copy(problem = message)
      else -> checkNotNull(current).thread.append(ChatMessage.Notice(message))
    }
  }

  private fun reportStandalone(current: NewTaskChat?, kind: NewTaskKind, outcome: NewTaskStartOutcome.Started) {
    val name = kind.startName()
    if (ownsChat(current)) {
      checkNotNull(current).thread.append(ChatMessage.Notice("Started $name. Follow it in Work."))
    } else {
      toasts.show(ToastMessage(ToastTone.Confirmation, "Started $name"))
    }
    val awaitFailure = outcome.awaitFailure ?: return
    scope.launch {
      val reason = awaitFailure() ?: return@launch
      showStartProblem(current, kind, "$name stopped. $reason")
    }
  }

  private fun clearChatFeedback() {
    offerState.value = offerState.value.copy(prefixKind = null, choices = emptyList(), action = null, problem = null, started = null)
  }
}

private val RUN_KINDS = setOf(NewTaskKind.GoalRun, NewTaskKind.PlanOnly)
