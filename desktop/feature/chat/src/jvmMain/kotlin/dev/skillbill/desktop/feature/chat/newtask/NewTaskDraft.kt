package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.common.Diagnostics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.SavedChatProjection
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.toNewTaskKind
import dev.skillbill.desktop.core.domain.repository.ApprovalFeedRepository
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.ChatOperationCardActions
import dev.skillbill.desktop.feature.chat.ChatThread
import dev.skillbill.desktop.feature.chat.OperationCardCoordinator
import dev.skillbill.desktop.feature.chat.OperationCardRequests
import dev.skillbill.desktop.feature.chat.RunOpener
import dev.skillbill.desktop.feature.chat.START_AGAIN_SUFFIX
import dev.skillbill.desktop.feature.chat.UnavailableApprovalFeed
import dev.skillbill.desktop.feature.chat.UnavailableRunOpener
import dev.skillbill.desktop.feature.chat.chipText
import dev.skillbill.desktop.feature.chat.restored
import dev.skillbill.desktop.feature.chat.restoredDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

data class NewTaskRepository(val root: String, val name: String)

data class NewTaskOffer(
  val starting: Boolean = false,
  val prefixKind: NewTaskKind? = null,
  val choices: List<String> = emptyList(),
  val action: ChatSuggestedAction? = null,
  val started: String? = null,
  val selectedKey: String? = null,
)

private val GOAL_KINDS = setOf(NewTaskKind.GoalRun, NewTaskKind.PlanOnly)
private fun NewTaskKind.startName(): String =
  NewTaskActionKind.entries.firstOrNull { it.toNewTaskKind() == this }?.wire ?: name.lowercase()

/** One open New task chat: what the operator set up for it, with its conversation. [NewTaskChats] owns the open set for the window. */
class NewTaskDraft internal constructor(
  private val chats: ExecutionChatRepository,
  private val workControls: WorkControlRepository,
  private val databaseSession: DatabaseSessionController,
  private val toasts: ToastQueue,
  windowScope: WindowCoroutineScope,
  private val clock: Clock,
  private val approvals: ApprovalFeedRepository = UnavailableApprovalFeed,
  private val savedChats: NewTaskSavedChats = NewTaskSavedChats(chats, windowScope.scope, toasts),
  private val changesRepository: ChatChangesRepository = UnavailableChatChanges,
  private val diagnostics: Diagnostics,
  private val runs: RunOpener = UnavailableRunOpener,
  cardRequests: OperationCardRequests = OperationCardRequests(),
) {
  private val scope = windowScope.scope
  private val cardOwners = IdentityHashMap<ChatThread, NewTaskChat>()
  private val retainedThreads = ConcurrentHashMap<String, ChatThread>()
  private val saveLocks = ConcurrentHashMap<String, Mutex>()
  private val invalidDrafts = ConcurrentHashMap.newKeySet<String>()
  private val cards = OperationCardCoordinator(workControls, scope, { kind ->
    val agent = selectedAgent
    val repo = repository
    val open = databaseSession.state.value as? DatabaseSessionState.Open
    if (agent == null || repo == null || open == null) {
      null
    } else {
      NewTaskStart(
        repo.root,
        "",
        baseBranch.ifBlank { null },
        agent.agentId,
        kind,
        null,
        open.sessionId,
        profile = agent.profile,
      )
    }
  }, { thread ->
    synchronized(cardOwners) { cardOwners[thread] }?.persist()?.also { if (it) refreshSaved() } ?: false
  }, runs = runs, requests = cardRequests)
  private val chatState = MutableStateFlow<NewTaskChat?>(null)
  private val offerState = MutableStateFlow(NewTaskOffer())
  private var previewTracker: Pair<String, NewTaskChatChanges>? = null
  private var closing = false
  private var offerKeyEdit: NewTaskOfferKeyEdit? = null
  private var lastOfferFieldValue: String? = null
  internal var chatDispatcher = Dispatchers.IO

  var text: String by mutableStateOf("")
  internal var images: List<ChatImage> by mutableStateOf(emptyList())
    private set
  private var repositoryState: NewTaskRepository? by mutableStateOf(null)
  private var acceptedKeyState: String? by mutableStateOf(null)
  internal var issueKeyProposals: List<String> by mutableStateOf(emptyList())
    private set
  var repository: NewTaskRepository?
    get() = repositoryState
    set(value) {
      if (repositoryState?.root != value?.root) {
        previewTracker?.second?.end()
        previewTracker = null
        branchChoiceRequested = false
        acceptedKeyState = null
        issueKeyProposals = emptyList()
        offerState.value = NewTaskOffer()
      }
      repositoryState = value
    }
  var addedRepositories: List<NewTaskRepository> by mutableStateOf(emptyList())
  internal var branchChoiceRequested: Boolean by mutableStateOf(false)
  var baseBranch: String by mutableStateOf("")
  internal var branchSwitching: Boolean by mutableStateOf(false)
  var agentId: String? by mutableStateOf(null)

  /** True once the operator picks an agent for this draft; until then it follows the default agent setting. */
  internal var agentChosen = false
  var selectedAgent: ChatAgent? by mutableStateOf(null)

  internal val chat: StateFlow<NewTaskChat?> = chatState.asStateFlow()

  /** The Confirm, Dismiss, Adjust and option callbacks of the operation cards in [current]'s transcript. */
  internal fun operationCardActions(current: NewTaskChat): ChatOperationCardActions =
    cards.actions(current.thread).copy(handOffVerify = true)

  internal val offer: StateFlow<NewTaskOffer> = offerState.asStateFlow()

  internal val acceptedIssueKey: String?
    get() = acceptedKeyState.takeIf { chatState.value?.repositoryRoot == repository?.root }
  internal var cursorMcpDependency by mutableStateOf(CursorMcpDependency.ProjectUserOnly)

  /** Every repository's saved drafts, newest first, as of the latest [refreshSaved]. */
  internal val saved: StateFlow<SavedChatProjection> = savedChats.saved

  internal fun addPastedImage(image: ChatImage) {
    if (closing) return
    cursorMcpDependency = CursorMcpDependency.ProjectUserOnly
    images = images + image
  }

  internal fun removePastedImage(index: Int) {
    images = images.filterIndexed { position, _ -> position != index }
  }

  internal fun canSend(): Boolean = !branchSwitching && canAdmitTurn(text, images)

  private fun canAdmitTurn(message: String, attachments: List<ChatImage> = emptyList()): Boolean {
    val current = chatState.value
    return !closing && (message.isNotBlank() || attachments.isNotEmpty()) && repository != null && selectedAgent != null && selectedAgent?.agentId == agentId &&
      !openCodeContextChanged(current) &&
      current?.repositoryMissing != true && !offerState.value.starting
  }

  private fun openCodeContextChanged(current: NewTaskChat?): Boolean {
    if (current?.agent?.agentId != "opencode") return false
    val selected = selectedAgent ?: return true
    return repository?.root != current.repositoryRoot || baseBranch.ifBlank { null } != current.baseBranch ||
      selected.agentId != current.agent.agentId || selected.profile != current.agent.profile
  }

  /** Sends the text and images, opening the chat on the first message, and clears the draft. Requires [canSend]. */
  internal fun send() {
    if (!canSend()) return
    sendTurn(text, null, images, cursorMcpDependency)
  }

  internal fun sendSuggestedAction(action: ChatSuggestedAction): Boolean {
    if (!canAdmitTurn(action.instruction) || chatState.value == null) return false
    sendTurn(action.instruction, action)
    return true
  }

  internal fun stop() {
    val returned = chatState.value?.stop().orEmpty()
    if (returned.isNotEmpty()) {
      cursorMcpDependency = if (text.isBlank() && images.isEmpty()) {
        returned.map { it.cursorMcpDependency }.distinct().singleOrNull() ?: CursorMcpDependency.ProjectUserOnly
      } else CursorMcpDependency.ProjectUserOnly
      text = restoredDraft(returned, text)
      images = returned.flatMap { it.images } + images
    }
  }

  internal fun sendNow() {
    chatState.value?.sendNow()
  }

  private fun sendTurn(
    submitted: String,
    action: ChatSuggestedAction?,
    attachments: List<ChatImage> = emptyList(),
    cursorMcpDependency: CursorMcpDependency = CursorMcpDependency.ProjectUserOnly,
  ) {
    val current = chatState.value ?: open(UUID.randomUUID().toString(), checkNotNull(repository).root, baseBranch.ifBlank { null }, checkNotNull(selectedAgent))
    if (openCodeContextChanged(current)) {
      current.thread.append(ChatMessage.Notice("This OpenCode conversation is bound to its saved repository, branch and profile. Start a new chat to use the changed selection."))
      return
    }
    offerState.value = offerState.value.copy(prefixKind = null, choices = emptyList(), action = null, started = null, selectedKey = null)
    if (action == null && attachments.isEmpty() && cards.submitAdjustment(current.thread, submitted)) {
      if (text == submitted) text = ""
      return
    }
    current.send(
      submitted,
      action,
      images = attachments,
      onAccepted = {
        if (chatState.value === current && repository?.root == current.repositoryRoot) {
          val selection = listOf(ChatMessage.Operator(submitted, action))
          newTaskOperatorIdentity(selection)?.let { acceptedKeyState = it.uppercase() }
          issueKeyProposals = (newTaskIssueKeyProposals(selection) + issueKeyProposals).distinct()
        }
        if (action == null) {
          if (text == submitted) text = ""
          if (images == attachments) images = emptyList()
        }
      },
      cursorMcpDependency = cursorMcpDependency,
      onRejected = {
        if (action == null && text.isBlank() && images.isEmpty()) {
          text = submitted
          images = attachments
          this.cursorMcpDependency = cursorMcpDependency
        }
      },
    )
  }

  internal fun offerKeyChanged(value: String) {
    val previous = lastOfferFieldValue
    lastOfferFieldValue = value
    if (previous == value) return
    val current = chatState.value
    val identity = newTaskCompleteKeyOrUrl(value)?.takeIf(NEW_TASK_COMPLETE_KEY::matches)
    val previousIdentity = previous?.let(::newTaskCompleteKeyOrUrl)?.takeIf(NEW_TASK_COMPLETE_KEY::matches)
    if (identity == null || identity.equals(previousIdentity, ignoreCase = true) || current == null) {
      offerKeyEdit = null
      return
    }
    val transcript = current.thread.transcript.value
    offerKeyEdit = NewTaskOfferKeyEdit(
      current,
      current.repositoryRoot,
      value,
      transcript.size,
      transcript.filterIsInstance<ChatMessage.Operator>(),
    )
  }

  internal fun offerKeyFieldDisposed() {
    offerKeyEdit = null
    lastOfferFieldValue = null
  }

  internal fun start(
    kind: NewTaskKind,
    keyChoice: String? = null,
    target: String? = null,
    action: ChatSuggestedAction? = null,
    offerFieldValue: String? = null,
  ) {
    val current = chatState.value
    if (closing || offerState.value.starting || current?.thread?.busy?.value == true) return
    if (current == null && kind in RUN_KINDS) return
    if (kind in RUN_KINDS && offerState.value.started != null) return
    if (selectedAgent == null || repository == null) return
    val pending = offerState.value
    val selectedAction = action ?: pending.action?.takeIf { pending.prefixKind == kind }
    offerState.value = NewTaskOffer(starting = true, started = pending.started)
    startRequest(kind, keyChoice, target, selectedAction, offerFieldValue, current)
  }

  internal fun selectIssueKey(keyChoice: String) {
    val pending = offerState.value
    val kind = pending.prefixKind ?: return
    if (newTaskStartArgumentIdentity(keyChoice)?.let(NEW_TASK_COMPLETE_KEY::matches) != true) return
    val current = chatState.value
    if (kind !in GOAL_KINDS || closing || pending.starting || current?.thread?.busy?.value == true) return
    if (current == null || pending.started != null) return
    if (selectedAgent == null || repository == null) return
    acceptedKeyState = keyChoice.trim().uppercase()
    offerState.value = pending.copy(selectedKey = acceptedKeyState)
  }

  private fun startRequest(
    kind: NewTaskKind,
    keyChoice: String?,
    target: String?,
    action: ChatSuggestedAction?,
    offerFieldValue: String?,
    current: NewTaskChat?,
  ) {
    val agent = checkNotNull(selectedAgent)
    val root = checkNotNull(repository).root
    if (current != null && current.repositoryRoot != root) {
      scope.launch { showStartProblem(current, kind, "The repository changed. Open a chat for the selected repository and choose its issue key.") }
      offerState.value = NewTaskOffer()
      return
    }
    val transcript = current?.thread?.transcript?.value.orEmpty()
    val requirements = if (kind in GOAL_KINDS && action != null) action.instruction else {
      listOfNotNull(action?.instruction, newTaskRequirements(transcript))
        .filter(String::isNotBlank)
        .joinToString("\n\n")
    }
    val title = if (kind in GOAL_KINDS && action != null) action.title else action?.title ?: current?.let { newTaskTitle(transcript) }
    val argumentIdentity = newTaskStartArgumentIdentity(keyChoice)
    val resolvedKey = if (kind in GOAL_KINDS && keyChoice == null) acceptedIssueKey else argumentIdentity ?: keyChoice
    val reuseRequested = kind in GOAL_KINDS && resolvedKey != null &&
      newTaskReuseRequested(transcript, resolvedKey, offerFieldValue, offerKeyEdit, current, root)
    val expectedSessionId = current?.databaseSessionId ?: if (current == null) {
      (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId
    } else null
    val startRequest = NewTaskStart(
      root,
      requirements,
      baseBranch.ifBlank { null },
      agent.agentId,
      kind,
      resolvedKey,
      expectedSessionId,
      title = title,
      target = target?.trim()?.takeIf(String::isNotBlank) ?: action?.target?.trim()?.takeIf(String::isNotBlank),
      reuseRequested = reuseRequested,
      branch = action?.branch?.trim()?.takeIf(String::isNotBlank),
      profile = agent.profile,
    )
    scope.launch {
      try {
        val outcome = workControls.startNewTask(startRequest)
        when (outcome) {
          is NewTaskStartOutcome.Started -> {
            if (kind in GOAL_KINDS) {
              if (ownsChat(current)) offerKeyEdit = null
              val message = when (outcome.kind) {
                NewTaskKind.GoalRun -> "Started goal run ${outcome.issueKey}"
                NewTaskKind.PlanOnly -> "Started plan ${outcome.issueKey}"
                else -> "Started ${outcome.kind.name.lowercase()}"
              }
              if (ownsChat(current)) {
                offerState.value = NewTaskOffer(started = message)
                record(checkNotNull(current), ChatMessage.Notice("$message. $START_AGAIN_SUFFIX", confirmation = true))
              } else {
                toasts.show(ToastMessage(ToastTone.Confirmation, message))
              }
              observeRunFailure(current, kind, outcome)
            } else {
              reportStandalone(current, kind, outcome, startRequest)
            }
          }
          is NewTaskStartOutcome.AwaitingConfirmation -> {
            cards.remember(outcome.launchId, startRequest)
            showParkedCard(current, kind, outcome.launchId)
          }
          is NewTaskStartOutcome.NeedsKey -> {
            if (ownsChat(current)) {
              offerState.value = NewTaskOffer(starting = true, prefixKind = kind, choices = (issueKeyProposals + outcome.choices).distinct(), action = action)
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

  /** Ends the chat and deletes its saved draft; the selections and any typed text stay. False when there was no chat to discard. */
  internal suspend fun discard(): Boolean {
    if (closing) return false
    val current = chatState.value ?: return false
    val saveLock = saveLocks[current.sessionId]
    if (current.thread.deferDestructiveAction("discard this draft")) return false
    return leave(current, beforeLeave = {
      invalidDrafts += current.sessionId
      retainedThreads.remove(current.sessionId)
      saveLocks.remove(current.sessionId)
      synchronized(cardOwners) { cardOwners.remove(current.thread) }
    }) {
      saveLock?.withLock { chats.deleteSavedChat(current.sessionId) } ?: chats.deleteSavedChat(current.sessionId)
    }
  }

  /** Discards [entry], allowing undo unless it is the open chat. */
  internal fun discardSaved(entry: ChatHistoryEntry) {
    if (closing) return
    if (chatState.value?.sessionId == entry.sessionId) {
      scope.launch { discard() }
      return
    }
    savedChats.discardSaved(entry)
  }

  /** Writes any running reply, then ends the chat's agent process and keeps its saved draft, for "New chat" and a window close. */
  internal suspend fun close(): Boolean {
    if (closing) return false
    val current = chatState.value ?: return true
    closing = true
    return try {
      if (!current.flush()) false else leave(current) {}
    } finally {
      closing = false
    }
  }

  internal fun launch(block: suspend () -> Unit) = scope.launch { block() }

  internal fun previewChanges(root: String): NewTaskChatChanges {
    previewTracker?.takeIf { it.first == root }?.let { return it.second }
    previewTracker?.second?.end()
    return NewTaskChatChanges(
      changesRepository, scope, chatDispatcher, root, null, resumed = false, repositoryMissing = false,
      captureAttempted = false, initialAttributedPaths = null,
    ).also { previewTracker = root to it }
  }

  internal suspend fun models(agent: ChatAgent, root: String) = chats.models(agent, root)

  /** Opens a new agent session on the current repository, branch and agent, keeping the transcript. */
  internal fun rebindChat() {
    val current = chatState.value ?: return
    val agent = selectedAgent ?: return
    val repo = repository ?: return
    val branch = baseBranch.ifBlank { null }
    if (current.agent.agentId == "opencode" &&
      (current.repositoryRoot != repo.root || current.baseBranch != branch || current.agent.agentId != agent.agentId || current.agent.profile != agent.profile)) {
      val notice = "This OpenCode conversation is bound to its saved repository, branch and profile. Start a new chat to use the changed selection."
      if (current.thread.transcript.value.lastOrNull()?.let { it is ChatMessage.Notice && it.text == notice } != true) {
        current.thread.append(ChatMessage.Notice(notice))
      }
      return
    }
    if (closing || current.repositoryMissing || current.thread.busy.value || offerState.value.starting) return
    if (current.repositoryRoot == repo.root && current.baseBranch == branch &&
      current.agent.agentId == agent.agentId && current.agent.profile == agent.profile) {
      current.changeModel(agent)
      return
    }
    scope.launch { rebindChat(current) }
  }

  private suspend fun rebindChat(current: NewTaskChat) {
    val rebind = synchronized(current.transitionLock) {
      if (current.deferAcceptedSave { rebindChat() } || current.thread.deferDestructiveAction("change the draft context")) return
      val agent = selectedAgent ?: return
      val repo = repository ?: return
      if (closing || current.repositoryMissing || current.thread.busy.value || offerState.value.starting) return
      val branch = baseBranch.ifBlank { null }
      if (current.repositoryRoot == repo.root && current.baseBranch == branch && current.agent.agentId == agent.agentId && current.agent.profile == agent.profile) {
        current.changeModel(agent)
        return
      }
      val lines = current.thread.transcript.value
      val previousId = current.sessionId
      val replay = (current.pendingReplay?.takeIf { !current.started }
        ?: NewTaskReplay(current.agent.agentId, current.repositoryRoot, current.baseBranch, lines))
        .takeIf { newTaskReplayPreamble(it, repo.root, branch) != null }
      Rebind(agent, repo, branch, lines, previousId, replay)
    } ?: return
    val (agent, repo, branch, lines, previousId, replay) = rebind
    if (!current.end()) return
    val sameRepository = current.repositoryRoot == repo.root
    val next = open(UUID.randomUUID().toString(), repo.root, branch, agent, replay = replay,
      changesBaseline = current.changesBaseline.takeIf { sameRepository },
      resumedChanges = current.changesResumed && sameRepository,
      changesCaptureAttempted = (current.changesCaptureAttempted || current.started || current.changesBaseline != null) && sameRepository,
      changesAttributedPaths = current.changesAttributedPaths.takeIf { sameRepository })
    val switched = "Switched to ${chipText(agent)} in ${repo.name}."
    val notice = if (replay != null) "$switched It gets the earlier conversation with your next message." else switched
    next.thread.showCapability(agent)
    next.thread.replaceTranscript(lines + ChatMessage.Notice(notice))
    scope.launch {
      current.job.join()
      current.awaitAcceptedSaves()
      if (!cards.hasOutstanding(current.thread) && !current.thread.hasDeliveryClaim()) {
        val lock = saveLocks[previousId]
        if (lock != null) {
          lock.withLock {
            chats.deleteSavedChat(previousId)
            retainedThreads.remove(previousId, current.thread)
            saveLocks.remove(previousId, lock)
            synchronized(cardOwners) { cardOwners.remove(current.thread) }
          }
        } else chats.deleteSavedChat(previousId)
      }
      if (!next.persist() && chatState.value === next) {
        next.thread.append(ChatMessage.Notice("This draft could not be saved. Keep it open and try again."))
      }
      refreshSaved()
    }
  }

  private data class Rebind(
    val agent: ChatAgent,
    val repository: NewTaskRepository,
    val branch: String?,
    val lines: List<ChatMessage>,
    val previousId: String,
    val replay: NewTaskReplay?,
  )

  /** Opens [entry] in this empty draft on its agent session, or with only Discard offered when its repository no longer exists. */
  internal fun resume(entry: ChatHistoryEntry): Boolean {
    val context = entry.newTask ?: return false
    if (entry.sessionId in invalidDrafts || chatState.value != null) return false
    repository = NewTaskRepository(context.repositoryRoot, File(context.repositoryRoot).name)
    baseBranch = context.baseBranch.orEmpty()
    agentId = entry.agent.agentId
    selectedAgent = entry.agent
    val missing = !File(context.repositoryRoot).isDirectory
    val retained = retainedThreads[entry.sessionId]
    val resumed = open(
      entry.sessionId,
      context.repositoryRoot,
      context.baseBranch,
      entry.agent,
      context.sessionEstablished,
      context.pendingTurnLineIndex,
      missing,
      changesBaseline = context.changesBaseline,
      resumedChanges = context.changesBaseline != null,
      changesCaptureAttempted = context.changesCaptureAttempted || context.sessionEstablished || context.changesBaseline != null,
    )
    val lines = if (retained == null) entry.lines.map(::restored) else emptyList()
    val notice = if (entry.agent.agentId == "opencode") {
      ChatMessage.Notice("This draft's repository no longer exists: ${context.repositoryRoot}. Its saved OpenCode history stays available. Start a new chat to continue elsewhere.")
    } else ChatMessage.Notice("This draft's repository no longer exists: ${context.repositoryRoot}. Discard it to continue.")
    if (retained == null) resumed.thread.replaceTranscript(if (missing) lines + notice else lines)
    resumed.thread.showCapability(entry.agent)
    synchronized(cardOwners) { cardOwners[resumed.thread] = resumed }
    if (!missing) cards.revalidate(resumed.thread, resumed.thread.transcript.value.mapNotNull { (it as? ChatMessage.Notice)?.operationCard?.token })
    return true
  }

  internal fun refreshSaved() {
    savedChats.refresh()
  }

  internal fun dispose() {
    scope.launch { disposeAndAwait() }
  }

  internal suspend fun disposeAndAwait() {
    previewTracker?.second?.end()
    previewTracker = null
    val current = chatState.value
    if (current?.end() == false) return
    chatState.value = null
    images = emptyList()
    clearChatFeedback()
  }

  private fun open(
    sessionId: String,
    root: String,
    branch: String?,
    agent: ChatAgent,
    started: Boolean = false,
    pendingTurnLineIndex: Int? = null,
    missing: Boolean = false,
    replay: NewTaskReplay? = null,
    changesBaseline: ChatChangesBaseline? = null,
    resumedChanges: Boolean = false,
    changesCaptureAttempted: Boolean = false,
    changesAttributedPaths: Set<String>? = null,
  ): NewTaskChat {
    val openedDatabaseSessionId = (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId
    val thread = retainedThreads.getOrPut(sessionId) { ChatThread(clock) }
    val saveLock = saveLocks.getOrPut(sessionId) { Mutex() }
    return NewTaskChat(
      chats,
      scope,
      clock,
      sessionId,
      openedDatabaseSessionId,
      root,
      branch,
      agent,
      started,
      pendingTurnLineIndex,
      missing,
      replay,
      chatDispatcher,
      ::refreshSaved,
      approvals,
      thread,
      saveLock,
      { sessionId !in invalidDrafts && retainedThreads[sessionId] === thread },
      changesRepository,
      changesBaseline,
      resumedChanges,
      changesCaptureAttempted,
      changesAttributedPaths,
      previewTracker?.takeIf { it.first == root && changesBaseline == null && !started }?.second?.also { previewTracker = null },
      diagnostics,
      { openedDatabaseSessionId != null && (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId == openedDatabaseSessionId },
    ).also {
      clearChatFeedback()
      chatState.value = it
      synchronized(cardOwners) { cardOwners[thread] = it }
    }
  }

  // Waits for the cancelled turn first, so a save it had in flight cannot recreate a deleted draft or miss the refreshed list.
  private suspend fun leave(current: NewTaskChat, beforeLeave: () -> Unit = {}, afterEnd: suspend () -> Unit): Boolean {
    if (!current.end()) return false
    beforeLeave()
    if (chatState.value === current) {
      chatState.value = null
      acceptedKeyState = null
      issueKeyProposals = emptyList()
      clearChatFeedback()
    }
    scope.launch {
      current.job.join()
      afterEnd()
      refreshSaved()
    }
    return true
  }

  private fun ownsChat(current: NewTaskChat?): Boolean = current != null && chatState.value === current

  private suspend fun showStartProblem(current: NewTaskChat?, kind: NewTaskKind, message: String) {
    when {
      !ownsChat(current) -> toasts.show(ToastMessage(ToastTone.Problem, message))
      kind in GOAL_KINDS -> record(checkNotNull(current), ChatMessage.Error(null, message))
      else -> checkNotNull(current).thread.append(ChatMessage.Notice(message))
    }
  }

  private suspend fun record(current: NewTaskChat, message: ChatMessage) {
    current.thread.append(message)
    if (!current.persist() && ownsChat(current)) {
      current.thread.append(ChatMessage.Notice("This draft could not be saved. Keep it open and try again."))
    }
  }

  /** Shows the card [launchId] parked; a run that ended cleanly without one has nothing to show when [silentWhenAbsent]. */
  private suspend fun showParkedCard(current: NewTaskChat?, kind: NewTaskKind, launchId: String, silentWhenAbsent: Boolean = false) {
    val chat = current ?: return
    if (chat.sessionId in invalidDrafts || retainedThreads[chat.sessionId] !== chat.thread) return
    val shown = cards.show(chat.thread, launchId)
    val owner = synchronized(cardOwners) { cardOwners[chat.thread] }
    if (shown && owner?.persist() == true) refreshSaved()
    val visible = chatState.value?.thread === chat.thread
    when {
      !visible && !silentWhenAbsent -> toasts.show(ToastMessage(ToastTone.Confirmation, "${kind.startName()} is waiting for confirmation."))
      shown -> Unit
      !silentWhenAbsent ->
        showStartProblem(current, kind, "${kind.startName()} is waiting for confirmation, but its card could not be read.")
    }
  }

  private fun reportStandalone(current: NewTaskChat?, kind: NewTaskKind, outcome: NewTaskStartOutcome.Started, request: NewTaskStart) {
    val name = kind.startName()
    if (ownsChat(current)) {
      checkNotNull(current).thread.append(ChatMessage.Notice("Started $name. Follow it in Work."))
    } else {
      toasts.show(ToastMessage(ToastTone.Confirmation, "Started $name"))
    }
    val launchId = outcome.launchId
    launchId?.let { cards.remember(it, request) }
    observeRunFailure(current, kind, outcome) {
      if (launchId != null) showParkedCard(current, kind, launchId, silentWhenAbsent = true)
    }
  }

  /** Reports why a started run stopped; [onCleanExit] runs instead when it ended without a failure. */
  private fun observeRunFailure(
    current: NewTaskChat?,
    kind: NewTaskKind,
    outcome: NewTaskStartOutcome.Started,
    onCleanExit: suspend () -> Unit = {},
  ) {
    val awaitFailure = outcome.awaitFailure ?: return
    val startedMessage = offerState.value.started
    scope.launch {
      val reason = awaitFailure()
      if (reason == null) {
        onCleanExit()
        return@launch
      }
      if (ownsChat(current) && offerState.value.started == startedMessage) offerState.value = offerState.value.copy(started = null)
      val name = if (kind in GOAL_KINDS) "${kind.startName()} ${outcome.issueKey}" else kind.startName()
      showStartProblem(current, kind, "$name stopped. $reason")
    }
  }

  private fun clearChatFeedback() {
    offerKeyEdit = null
    lastOfferFieldValue = null
    offerState.value = offerState.value.copy(prefixKind = null, choices = emptyList(), action = null, started = null, selectedKey = null)
  }
}

private val RUN_KINDS = setOf(NewTaskKind.GoalRun, NewTaskKind.PlanOnly)
