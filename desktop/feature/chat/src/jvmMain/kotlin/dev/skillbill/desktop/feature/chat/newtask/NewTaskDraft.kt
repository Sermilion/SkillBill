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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

data class NewTaskRepository(val root: String, val name: String)

internal data class NewTaskStartOrigin(
  val draft: NewTaskDraft,
  val chat: NewTaskChat?,
  val contextRevision: Long,
  val selectedRoot: String?,
  val expectedDatabaseSessionId: Long? = null,
)

private data class OperationCardOrigin(
  val draft: NewTaskDraft,
  val chat: NewTaskChat,
  val contextRevision: Long,
  val selectedRoot: String,
  val databaseSessionId: Long,
)

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
  private val userHome: String = "",
  private val onResumePublished: (NewTaskDraft) -> Unit = {},
  private val onResumeFailed: (NewTaskDraft) -> Unit = {},
  private val cardRequests: OperationCardRequests = OperationCardRequests(),
) {
  private val scope = windowScope.scope
  private val cardOwners = IdentityHashMap<ChatThread, NewTaskChat>()
  private val retainedThreads = ConcurrentHashMap<String, ChatThread>()
  private val saveLocks = ConcurrentHashMap<String, Mutex>()
  private val invalidDrafts = ConcurrentHashMap.newKeySet<String>()
  private val cards: OperationCardCoordinator = OperationCardCoordinator(workControls, scope, { kind ->
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
  }, runs = runs, requests = cardRequests, admissionProblem = { thread, request, authority ->
    val origin = authority as? OperationCardOrigin
    val current = chatState.value
    when {
      repository == null -> SELECT_REPOSITORY_START_TEXT
      origin == null -> "This saved proposal has no current repository authority. Start a fresh proposal from this chat."
      contextTransitionOwner != null || contextTransitioning -> "Repository selection is changing. Select the repository again, then start a fresh action."
      current == null || current.thread !== thread -> "This saved proposal has no current chat authority. Start a fresh proposal from this chat."
      openDatabaseSessionId != current.databaseSessionId -> "The database changed. Reopen this chat before continuing the operation."
      origin != null && (origin.draft !== this || origin.chat !== current || origin.contextRevision != contextRevision ||
        origin.selectedRoot != repository?.root || origin.databaseSessionId != current.databaseSessionId) -> STALE_START_TEXT
      request != null && (request.repoRoot != repository?.root || request.expectedDatabaseSessionId != current.databaseSessionId) -> STALE_START_TEXT
      closing || startClaim != null || resumeTransition != null || offerState.value.starting -> "Finish the current operation before starting another."
      current.transitionBlocked || current.thread.busy.value -> "Finish the current chat activity before starting this operation."
      current.thread.hasDeliveryClaim() -> "A chat message is still waiting for delivery. Resolve it before starting work."
      current.hasAcceptedSaves() -> "A chat message is still being saved. Wait for it to finish, then start again."
      else -> null
    }
  }, decisionAdmissionProblem = { thread, decisionClaim ->
    if (cards.hasOtherOutstanding(thread, decisionClaim)) "Another operation is being submitted. Wait for it to finish, then try again." else null
  })
  private val chatState = MutableStateFlow<NewTaskChat?>(null)
  private val resumeSourceSessionsState = MutableStateFlow<Set<String>>(emptySet())
  internal val resumeSourceSessions: StateFlow<Set<String>> = resumeSourceSessionsState.asStateFlow()
  @Volatile private var resumeTransition: Job? = null
  @Volatile private var resumeTransitionOwner: Any? = null
  private val offerState = MutableStateFlow(NewTaskOffer())
  private var previewTracker: Pair<String, NewTaskChatChanges>? = null
  private var closing = false
  @Volatile private var startClaim: Any? = null
  @Volatile private var contextTransitionOwner: Any? = null
  @Volatile private var contextTransitionJob: Job? = null
  internal var contextTransitioning: Boolean by mutableStateOf(false)
    private set
  internal var onContextCommitted: ((NewTaskDraft) -> Unit)? = null
  internal var onRepositoryCandidate: suspend (NewTaskDraft, NewTaskChat, String, String, Long, Long) -> Unit = { _, _, _, _, _, _ -> }
  internal var contextRevision: Long = 0
    private set
  internal var repositoryRequestGeneration: Long = 0
    private set
  private var offerKeyEdit: NewTaskOfferKeyEdit? = null
  private var lastOfferFieldValue: String? = null
  internal var chatDispatcher = Dispatchers.IO

  var text: String by mutableStateOf("")
  internal var images: List<ChatImage> by mutableStateOf(emptyList())
    private set
  private var repositoryState: NewTaskRepository? by mutableStateOf(null)
  internal var initialRepositoryDefaultPending: Boolean = true
    private set
  private var acceptedKeyState: String? by mutableStateOf(null)
  internal var issueKeyProposals: List<String> by mutableStateOf(emptyList())
    private set
  var repository: NewTaskRepository?
    get() = repositoryState
    set(value) {
      initialRepositoryDefaultPending = false
      if (repositoryState?.root != value?.root) {
        contextRevision += 1
        previewTracker?.second?.end()
        previewTracker = null
        branchChoiceRequested = false
        acceptedKeyState = null
        issueKeyProposals = emptyList()
        offerState.value = NewTaskOffer()
        offerKeyEdit = null
        lastOfferFieldValue = null
        cursorMcpDependency = CursorMcpDependency.ProjectUserOnly
      }
      repositoryState = value
    }

  internal fun copyInitialRepositoryDefaultEligibilityFrom(source: NewTaskDraft) {
    initialRepositoryDefaultPending = source.initialRepositoryDefaultPending
  }
  var addedRepositories: List<NewTaskRepository> by mutableStateOf(emptyList())
  internal var branchChoiceRequested: Boolean by mutableStateOf(false)
  var baseBranch: String by mutableStateOf("")
  internal var branchSwitching: Boolean by mutableStateOf(false)
  var agentId: String? by mutableStateOf(null)

  /** True once the operator picks an agent for this draft; until then it follows the default agent setting. */
  internal var agentChosen = false
  var selectedAgent: ChatAgent? by mutableStateOf(null)

  internal val workingDirectory: String get() = repository?.root ?: userHome

  internal val chat: StateFlow<NewTaskChat?> = chatState.asStateFlow()
  internal val resuming: Boolean get() = resumeTransition != null
  internal val openDatabaseSessionId: Long? get() = (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId

  /** The Confirm, Dismiss, Adjust and option callbacks of the operation cards in [current]'s transcript. */
  internal fun operationCardActions(current: NewTaskChat): ChatOperationCardActions = cards.actions(current.thread, current.transitionLock)

  internal suspend fun awaitContextTransition(): Boolean {
    val pending = contextTransitionJob ?: return !contextTransitioning
    pending.join()
    return !contextTransitioning
  }

  internal fun beginRepositorySelectionRequest(): Long = ++repositoryRequestGeneration

  internal fun ownsCandidateOrigin(current: NewTaskChat, sessionId: String, revision: Long, requestGeneration: Long): Boolean =
    chatState.value === current && current.sessionId == sessionId && current.databaseSessionId != null &&
      contextRevision == revision && repositoryRequestGeneration == requestGeneration &&
      (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId == current.databaseSessionId

  internal val offer: StateFlow<NewTaskOffer> = offerState.asStateFlow()

  internal val acceptedIssueKey: String?
    get() = acceptedKeyState.takeIf { repository != null && chatState.value?.selectedRepositoryRoot == repository?.root }
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
    val openSession = databaseSession.state.value as? DatabaseSessionState.Open
    val databaseMatches = openSession != null && (current == null || current.databaseSessionId == openSession.sessionId)
    return !closing && startClaim == null && contextTransitionOwner == null && resumeTransition == null && databaseMatches && (message.isNotBlank() || attachments.isNotEmpty()) && workingDirectory.isNotBlank() && selectedAgent != null && selectedAgent?.agentId == agentId &&
      current?.thread?.agentSupported?.value != false && selectedAgent?.agentId?.let(chats::supports) == true &&
      current?.repositoryMissing != true && !offerState.value.starting && current?.transitionBlocked != true
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

  internal fun sendSuggestedActionForContext(revision: Long, root: String?, sessionId: Long?, action: ChatSuggestedAction): Boolean =
    if (contextFenceMatches(revision, root, sessionId)) sendSuggestedAction(action) else false

  internal fun selectIssueKeyForContext(revision: Long, root: String?, sessionId: Long?, key: String) {
    if (repository == null || root == null) reportStartRefusal(chatState.value, offerState.value.prefixKind ?: NewTaskKind.GoalRun, SELECT_REPOSITORY_START_TEXT)
    else if (contextFenceMatches(revision, root, sessionId)) selectIssueKey(key)
    else reportStartRefusal(chatState.value, offerState.value.prefixKind ?: NewTaskKind.GoalRun, STALE_START_TEXT)
  }

  private fun contextFenceMatches(revision: Long, root: String?, sessionId: Long?): Boolean =
    sessionId != null && contextRevision == revision && repository?.root == root && openDatabaseSessionId == sessionId &&
      (chatState.value == null || chatState.value?.databaseSessionId == sessionId)

  internal fun startForContext(
    revision: Long,
    expectedChat: NewTaskChat?,
    root: String?,
    kind: NewTaskKind,
    keyChoice: String?,
    action: ChatSuggestedAction?,
    target: String?,
    expectedSessionId: Long? = openDatabaseSessionId,
  ) {
    if (repository == null) {
      scope.launch { showStartProblem(expectedChat, kind, SELECT_REPOSITORY_START_TEXT) }
    } else if (root == null) {
      scope.launch { showStartProblem(expectedChat, kind, STALE_START_TEXT) }
    } else if (chatState.value === expectedChat && contextFenceMatches(revision, root, expectedSessionId)) {
      start(kind, keyChoice = keyChoice, target = target, action = action, origin = NewTaskStartOrigin(this, expectedChat, revision, root, expectedSessionId))
    } else {
      scope.launch { showStartProblem(expectedChat, kind, STALE_START_TEXT) }
    }
  }

  internal fun startAdmissionProblem(): String? {
    val current = chatState.value
    return when {
      repository == null -> SELECT_REPOSITORY_START_TEXT
      selectedAgent?.agentId?.let(chats::supports) != true -> "Choose a supported agent before starting work."
      closing || startClaim != null || contextTransitionOwner != null || resumeTransition != null || offerState.value.starting -> "Finish the current chat activity before starting work."
      current != null && (current.transitionBlocked || current.thread.busy.value) -> "Finish the current chat activity before starting work."
      current?.thread?.hasDeliveryClaim() == true -> "A chat message is still waiting for delivery. Resolve it before starting work."
      current?.hasAcceptedSaves() == true -> "A chat message is still being saved. Wait for it to finish, then start again."
      current != null && cards.hasOutstanding(current.thread) -> "Another chat operation is still being saved or submitted. Wait for it to finish, then try again."
      selectedAgent == null -> "Choose an agent before starting work."
      else -> null
    }
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
    val current = chatState.value ?: open(UUID.randomUUID().toString(), workingDirectory, repository?.root, baseBranch.ifBlank { null }, checkNotNull(selectedAgent))
    offerState.value = offerState.value.copy(prefixKind = null, choices = emptyList(), action = null, started = null, selectedKey = null)
    if (action == null && attachments.isEmpty()) {
      when (cards.submitAdjustment(current.thread, submitted, current.transitionLock)) {
        OperationCardCoordinator.AdjustmentSubmission.Accepted -> {
          if (text == submitted) text = ""
          return
        }
        OperationCardCoordinator.AdjustmentSubmission.Rejected -> return
        OperationCardCoordinator.AdjustmentSubmission.NotApplicable -> Unit
      }
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
    origin: NewTaskStartOrigin? = null,
  ) {
    val current = chatState.value
    if (repository == null) {
      scope.launch { showStartProblem(current, kind, SELECT_REPOSITORY_START_TEXT) }
      return
    }
    if (origin?.selectedRoot == null && origin != null) {
      scope.launch { showStartProblem(current, kind, STALE_START_TEXT) }
      return
    }
    if (current != null && current.databaseSessionId != openDatabaseSessionId) {
      scope.launch { showStartProblem(current, kind, "The database changed. Reopen this chat before starting work.") }
      return
    }
    val captured = origin ?: NewTaskStartOrigin(this, current, contextRevision, repository?.root, openDatabaseSessionId)
    if (captured.draft !== this || captured.chat !== current || captured.contextRevision != contextRevision ||
      captured.selectedRoot != repository?.root || captured.expectedDatabaseSessionId == null ||
      captured.expectedDatabaseSessionId != openDatabaseSessionId ||
      (current != null && (current.selectedRepositoryRoot != captured.selectedRoot || current.databaseSessionId != captured.expectedDatabaseSessionId))
    ) {
      scope.launch { showStartProblem(current, kind, STALE_START_TEXT) }
      return
    }
    if (closing || contextTransitionOwner != null || resumeTransition != null || offerState.value.starting ||
      current?.transitionBlocked == true || current?.thread?.busy?.value == true || current?.thread?.hasDeliveryClaim() == true ||
      current?.hasAcceptedSaves() == true || current?.let { cards.hasOutstanding(it.thread) } == true
    ) {
      val reason = when {
        current?.thread?.hasDeliveryClaim() == true -> "A chat message is still waiting for delivery. Resolve it before starting work."
        current?.hasAcceptedSaves() == true -> "A chat message is still being saved. Wait for it to finish, then start again."
        else -> "Finish the current chat activity before starting work."
      }
      scope.launch { showStartProblem(current, kind, reason) }
      return
    }
    if (current == null && kind in RUN_KINDS) {
      scope.launch { showStartProblem(null, kind, "Ask for a goal or plan start in the chat so its requirements are included.") }
      return
    }
    if (kind in RUN_KINDS && offerState.value.started != null) return
    if (selectedAgent == null) {
      scope.launch { showStartProblem(current, kind, "Choose an agent before starting work.") }
      return
    }
    val revision = captured.contextRevision
    val root = captured.selectedRoot ?: return
    val expectedSessionId = captured.expectedDatabaseSessionId
    if (expectedSessionId == null) {
      scope.launch { showStartProblem(current, kind, "The database session is not open. Reopen it before starting work.") }
      return
    }
    val claim = Any()
    val claimed = synchronized(current?.transitionLock ?: this) {
      if (startClaim != null || offerState.value.starting || current?.thread?.hasDeliveryClaim() == true ||
        current?.hasAcceptedSaves() == true || current?.transitionBlocked == true || current?.let { cards.hasOutstanding(it.thread) } == true
      ) false
      else {
        startClaim = claim
        true
      }
    }
    if (!claimed) {
      scope.launch { showStartProblem(current, kind, "A start is already being submitted. Wait for it to finish, then start again.") }
      return
    }
    val pending = offerState.value
    val selectedAction = action ?: pending.action?.takeIf { pending.prefixKind == kind }
    offerState.value = NewTaskOffer(starting = true, started = pending.started)
    startRequest(kind, keyChoice, target, selectedAction, offerFieldValue, current, revision, root, expectedSessionId, claim)
  }

  internal fun selectIssueKey(keyChoice: String) {
    val pending = offerState.value
    val kind = pending.prefixKind ?: return
    if (newTaskStartArgumentIdentity(keyChoice)?.let(NEW_TASK_COMPLETE_KEY::matches) != true) return
    val current = chatState.value
    if (kind !in GOAL_KINDS) return
    val fenceProblem = when {
      repository == null -> SELECT_REPOSITORY_START_TEXT
      current?.thread?.hasDeliveryClaim() == true -> "A chat message is still waiting for delivery. Resolve it before choosing an issue key."
      current?.hasAcceptedSaves() == true -> "A chat message is still being saved. Wait for it to finish, then choose the issue key again."
      closing || contextTransitionOwner != null || resumeTransition != null || pending.starting || startClaim != null ||
        current?.transitionBlocked == true || current?.thread?.busy?.value == true || current?.let { cards.hasOutstanding(it.thread) } == true ->
        "Finish the current chat activity before choosing an issue key."
      else -> null
    }
    if (fenceProblem != null) {
      scope.launch { showStartProblem(current, kind, fenceProblem) }
      return
    }
    if (current == null || pending.started != null) return
    if (repository == null) {
      scope.launch { showStartProblem(current, kind, SELECT_REPOSITORY_START_TEXT) }
      return
    }
    if (selectedAgent == null) return
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
    revision: Long,
    root: String,
    expectedSessionId: Long,
    claim: Any,
  ) {
    val agent = checkNotNull(selectedAgent)
    if (startClaim !== claim || repository?.root != root || contextRevision != revision ||
      (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId != expectedSessionId ||
      (current != null && (current.selectedRepositoryRoot != root || current.databaseSessionId != expectedSessionId)) ||
      current?.transitionBlocked == true || current?.thread?.busy?.value == true || current?.thread?.hasDeliveryClaim() == true ||
      current?.hasAcceptedSaves() == true || current?.let { cards.hasOutstanding(it.thread) } == true
    ) {
      scope.launch { showStartProblem(current, kind, STALE_START_TEXT) }
      if (startClaim === claim) startClaim = null
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
        if (startClaim !== claim || contextRevision != revision || repository?.root != root || chatState.value !== current ||
          (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId != expectedSessionId ||
          closing || contextTransitionOwner != null || resumeTransition != null ||
          current?.transitionBlocked == true || current?.thread?.busy?.value == true || current?.thread?.hasDeliveryClaim() == true ||
          current?.hasAcceptedSaves() == true || current?.let { cards.hasOutstanding(it.thread) } == true
        ) {
          showStartProblem(current, kind, STALE_START_TEXT)
          return@launch
        }
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
              reportStandalone(current, kind, outcome, startRequest, current?.let { OperationCardOrigin(this@NewTaskDraft, it, revision, root, expectedSessionId) })
            }
          }
          is NewTaskStartOutcome.AwaitingConfirmation -> {
            cards.remember(outcome.launchId, startRequest, current?.let { OperationCardOrigin(this@NewTaskDraft, it, revision, root, expectedSessionId) })
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
            showStartProblem(current, kind, outcome.message)
          }
          is NewTaskStartOutcome.Failed -> showStartProblem(current, kind, readProblemText(outcome.problem))
        }
      } finally {
        if (startClaim === claim) {
          startClaim = null
          offerState.value = offerState.value.copy(starting = false)
        }
      }
    }
  }

  /** Ends the chat and deletes its saved draft; the selections and any typed text stay. False when there was no chat to discard. */
  internal suspend fun discard(): Boolean {
    val current = chatState.value ?: return false
    synchronized(current.transitionLock) {
      if (closing || startClaim != null || contextTransitionOwner != null || resumeTransition != null ||
        offerState.value.starting || cards.hasDecisionOutstanding(current.thread) || current.transitionBlocked
      ) return false
      closing = true
    }
    val saveLock = saveLocks[current.sessionId]
    try {
      if (current.thread.deferDestructiveAction("discard this draft")) return false
      return leave(current, beforeLeave = {
        invalidDrafts += current.sessionId
        retainedThreads.remove(current.sessionId)
        saveLocks.remove(current.sessionId)
        synchronized(cardOwners) { cardOwners.remove(current.thread) }
      }) {
        saveLock?.withLock { chats.deleteSavedChat(current.sessionId) } ?: chats.deleteSavedChat(current.sessionId)
      }
    } finally {
      closing = false
    }
  }

  /** Discards [entry], allowing undo unless it is the open chat. */
  internal fun discardSaved(entry: ChatHistoryEntry) {
    val current = chatState.value
    if (closing || startClaim != null || contextTransitionOwner != null || resumeTransition != null || offerState.value.starting ||
      current?.let { cards.hasDecisionOutstanding(it.thread) } == true
    ) return
    if (chatState.value?.sessionId == entry.sessionId) {
      scope.launch { discard() }
      return
    }
    savedChats.discardSaved(entry)
  }

  /** Writes any running reply, then ends the chat's agent process and keeps its saved draft, for "New chat" and a window close. */
  internal suspend fun close(): Boolean {
    val current = chatState.value
    synchronized(current?.transitionLock ?: this) {
      if (closing || startClaim != null || contextTransitionOwner != null || resumeTransition != null ||
        offerState.value.starting || current?.let { cards.hasDecisionOutstanding(it.thread) || it.transitionBlocked } == true
      ) return false
      closing = true
    }
    return try {
      if (current == null) true else if (!current.flush()) false else leave(current) {}
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

  internal suspend fun agentFor(agentId: String) = chats.agentFor(agentId)

  /** Requests one committed context change. Existing chats keep their old projection until its save finishes. */
  internal fun rebindChat(
    requestedRepository: NewTaskRepository? = repository,
    requestedBranch: String = baseBranch,
    requestedAgent: ChatAgent? = selectedAgent,
  ): Boolean {
    if (startClaim != null) return false
    val agent = requestedAgent ?: return false
    val current = chatState.value
    if (current == null) {
      if (repository?.root == requestedRepository?.root && baseBranch == requestedBranch && selectedAgent == agent) return true
      val revision = contextRevision
      repository = requestedRepository
      baseBranch = requestedBranch
      agentId = agent.agentId
      selectedAgent = agent
      if (contextRevision == revision) contextRevision += 1
      onContextCommitted?.invoke(this)
      return true
    }
    val owner = Any()
    val requestedRoot = requestedRepository?.root
    val directory = requestedRoot ?: userHome
    val branch = requestedBranch.ifBlank { null }
    val databaseId = current.databaseSessionId ?: return false
    var queuedEntries: List<dev.skillbill.desktop.feature.chat.QueuedChatMessage> = emptyList()
    synchronized(current.transitionLock) {
      if (contextTransitionOwner != null || closing || startClaim != null || resumeTransition != null || current.thread.busy.value ||
        (current.thread.queued.value.isNotEmpty() && !current.repositoryCandidateSettling) || offerState.value.starting ||
        cards.hasOutstanding(current.thread) ||
        current.thread.deferDestructiveAction("change the draft context") ||
        (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId != databaseId
      ) return false
      if (current.selectedRepositoryRoot == requestedRoot && current.repositoryRoot == directory && current.baseBranch == branch && current.agent == agent) return true
      if (!cards.reserveContextTransition(current.thread)) return false
      if (current.repositoryCandidateSettling) {
        val detached = current.takeRepositoryTransitionQueue()
        if (detached == null) {
          cards.releaseContextTransition(current.thread)
          return false
        }
        queuedEntries = detached.map { it.copy(action = null, pauseAndAct = false, cursorMcpDependency = CursorMcpDependency.ProjectUserOnly) }
      }
      contextTransitionOwner = owner
      contextTransitioning = true
    }
    contextTransitionJob = scope.launch { rebindChat(current, owner, requestedRepository, requestedRoot, directory, branch, agent, databaseId, queuedEntries) }
    return true
  }

  private suspend fun rebindChat(
    current: NewTaskChat,
    owner: Any,
    requestedRepository: NewTaskRepository?,
    selectedRoot: String?,
    directory: String,
    branch: String?,
    agent: ChatAgent,
    databaseId: Long,
    queuedEntries: List<dev.skillbill.desktop.feature.chat.QueuedChatMessage>,
  ) {
    var staged: NewTaskChat? = null
    var oldEnded = false
    var cardsRevoked = false
    var committed = false
    var carriedQueue = queuedEntries
    try {
      if (directory.isBlank()) return
      current.awaitAcceptedSaves()
      if (contextTransitionOwner !== owner || chatState.value !== current || current.sessionId.isBlank() || !ownsDatabase(databaseId)) return
      val sameProviderContext = current.selectedRepositoryRoot == selectedRoot && current.repositoryRoot == directory &&
        current.baseBranch == branch && current.agent.agentId == agent.agentId && current.agent.profile == agent.profile
      if (sameProviderContext) {
        if (!current.changeModel(agent)) {
          contextProblem("This model choice could not be saved. The previous model is still selected.")
          return
        }
        agentId = agent.agentId
        selectedAgent = agent
        contextRevision += 1
        onContextCommitted?.invoke(this)
        refreshSaved()
        return
      }
      if (current.repositoryCandidateSettling) {
        val additional = current.takeRepositoryTransitionQueue() ?: run {
          contextProblem("This draft has a follow-up that must settle before its repository can change.")
          return
        }
        carriedQueue = carriedQueue + additional.map { it.copy(action = null, pauseAndAct = false, cursorMcpDependency = CursorMcpDependency.ProjectUserOnly) }
      }
      if (!current.end()) {
        contextProblem("This draft context could not be changed. The previous conversation is still available.")
        return
      }
      if (current.repositoryCandidateSettling) {
        val remaining = current.takeRepositoryTransitionQueue()
        if (remaining != null) carriedQueue = carriedQueue + remaining.map {
          it.copy(action = null, pauseAndAct = false, cursorMcpDependency = CursorMcpDependency.ProjectUserOnly)
        }
      }
      oldEnded = true
      current.job.join()
      chats.awaitEnd(current.sessionId)
      val oldRoot = current.selectedRepositoryRoot
      val sameRepository = oldRoot != null && oldRoot == selectedRoot
      val lines = current.thread.repositorySwitchTranscript()
      val replay = (current.pendingReplay?.takeIf { !current.started }
        ?: NewTaskReplay(current.agent.agentId, current.repositoryRoot, current.baseBranch, lines, oldRoot))
        .takeIf { newTaskReplayPreamble(it, directory, branch, selectedRoot) != null }
      staged = open(
        UUID.randomUUID().toString(), directory, selectedRoot, branch, agent,
        replay = replay,
        changesBaseline = current.changesBaseline.takeIf { sameRepository },
        resumedChanges = current.changesResumed && sameRepository,
        changesCaptureAttempted = (current.changesCaptureAttempted || current.started || current.changesBaseline != null) && sameRepository,
        changesAttributedPaths = current.changesAttributedPaths.takeIf { sameRepository },
        publish = false, active = false, databaseSessionId = databaseId,
      )
      val switched = "Switched to ${chipText(agent)} in ${File(directory).name}."
      val notice = if (replay != null) "$switched It gets the earlier conversation with your next message." else switched
      staged!!.thread.showCapability(agent)
      staged!!.thread.replaceTranscript(lines + ChatMessage.Notice(notice))
      staged!!.thread.replaceQueuedEntries(carriedQueue)
      val saved = staged!!.persist(refresh = false)
      if (!saved || contextTransitionOwner !== owner || chatState.value !== current || !ownsDatabase(databaseId)) {
        contextProblem("This draft context could not be saved. The previous conversation is still available.")
        return
      }
      cards.revoke(current.thread)
      cardsRevoked = true
      val priorRevision = contextRevision
      repository = requestedRepository
      if (contextRevision == priorRevision) contextRevision += 1
      baseBranch = branch.orEmpty()
      agentId = agent.agentId
      selectedAgent = agent
      chatState.value = staged
      committed = true
      clearChatFeedback()
      synchronized(cardOwners) { cardOwners[staged!!.thread] = staged!! }
      staged!!.activate()
      staged = null
      onContextCommitted?.invoke(this)
      if (!cards.hasOutstanding(current.thread) && !current.thread.hasDeliveryClaim()) {
        saveLocks[current.sessionId]?.withLock {
          if (contextTransitionOwner === owner && chatState.value !== current) {
            chats.deleteSavedChat(current.sessionId)
            retainedThreads.remove(current.sessionId, current.thread)
            saveLocks.remove(current.sessionId)
            synchronized(cardOwners) { cardOwners.remove(current.thread) }
          }
        }
      }
      refreshSaved()
    } catch (cancelled: CancellationException) {
      oldEnded = oldEnded || !current.job.isActive
      throw cancelled
    } catch (_: Exception) {
      oldEnded = oldEnded || !current.job.isActive
      contextProblem("This draft context could not be saved. The previous conversation is still available.")
    } finally {
      withContext(NonCancellable) {
        try {
          staged?.end()
          if (oldEnded && chatState.value === current && contextTransitionOwner === owner) recoverEndedContext(current)
        } finally {
          chatState.value?.let { destination ->
            current.finishRepositoryTransitionQueue(destination, if (committed) emptyList() else carriedQueue)
          }
          if (contextTransitionOwner === owner) {
            contextTransitionOwner = null
            contextTransitioning = false
          }
          if (!cardsRevoked) cards.releaseContextTransition(current.thread)
          if (contextTransitionOwner == null) contextTransitionJob = null
          chatState.value?.resumeQueuedMessages()
        }
      }
    }
  }

  private fun ownsDatabase(databaseId: Long): Boolean =
    (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId == databaseId

  private fun contextProblem(message: String) {
    toasts.show(ToastMessage(ToastTone.Problem, message))
  }

  private fun recoverEndedContext(current: NewTaskChat) {
    val recovered = open(
      current.sessionId, current.repositoryRoot, current.selectedRepositoryRoot, current.baseBranch, current.agent,
      started = current.started, pendingTurnLineIndex = null, replay = current.pendingReplay,
      changesBaseline = current.changesBaseline, resumedChanges = current.changesResumed,
      changesCaptureAttempted = current.changesCaptureAttempted, changesAttributedPaths = current.changesAttributedPaths,
      publish = false, active = current.databaseSessionId?.let(::ownsDatabase) == true, databaseSessionId = current.databaseSessionId,
    )
    recovered.thread.replaceTranscript(current.thread.transcript.value)
    chatState.value = recovered
    synchronized(cardOwners) { cardOwners[recovered.thread] = recovered }
  }

  /** Opens [entry] in this empty draft on its agent session; a missing repository requires selection before chat can continue. */
  internal fun resume(entry: ChatHistoryEntry): Boolean {
    val context = entry.newTask ?: return false
    return synchronized(chatState.value?.transitionLock ?: this) {
      val current = chatState.value
      if (entry.sessionId in invalidDrafts || current != null || contextTransitionOwner != null || resumeTransition != null ||
        closing || startClaim != null || offerState.value.starting || current?.let { cards.hasDecisionOutstanding(it.thread) } == true
      ) return@synchronized false

      val selectedRepositoryRoot = context.selectedRepositoryRoot
      val workingDirectory = selectedRepositoryRoot ?: userHome
      val homeChanged = selectedRepositoryRoot == null && context.workingDirectory != workingDirectory
      val missing = selectedRepositoryRoot != null && !File(selectedRepositoryRoot).isDirectory
      if (homeChanged) return@synchronized stageChangedHomeResume(entry, context, workingDirectory)
      repository = selectedRepositoryRoot?.let { NewTaskRepository(it, File(it).name) }
      baseBranch = context.baseBranch.orEmpty()
      agentId = entry.agent.agentId
      selectedAgent = entry.agent
      val retained = retainedThreads[entry.sessionId]
      val sessionId = entry.sessionId
      val replay = if (!context.sessionEstablished) NewTaskReplay(entry.agent.agentId, context.workingDirectory, context.baseBranch, entry.lines.map(::restored), selectedRepositoryRoot) else null
      val resumed = open(
        sessionId,
        workingDirectory,
        selectedRepositoryRoot,
        context.baseBranch,
        entry.agent,
        context.sessionEstablished,
        context.pendingTurnLineIndex,
        missing,
        replay = replay,
        changesBaseline = context.changesBaseline,
        resumedChanges = context.changesBaseline != null,
        changesCaptureAttempted = context.changesCaptureAttempted || context.sessionEstablished || context.changesBaseline != null,
        changesAttributedPaths = context.changesAttributedPaths,
      )
      val lines = if (retained == null) entry.lines.map(::restored) else emptyList()
      val notice = ChatMessage.Notice("This draft's repository no longer exists: ${selectedRepositoryRoot ?: context.repositoryRoot}. Select an available repository to continue.")
      if (retained == null) resumed.thread.replaceTranscript(if (missing) lines + notice else lines)
      resumed.thread.showCapability(entry.agent)
      resumed.thread.showUnavailableAgentNotice()
      synchronized(cardOwners) { cardOwners[resumed.thread] = resumed }
      if (!missing) cards.revalidate(resumed.thread, resumed.thread.transcript.value.mapNotNull { (it as? ChatMessage.Notice)?.operationCard?.token })
      true
    }
  }

  private fun stageChangedHomeResume(
    entry: ChatHistoryEntry,
    context: dev.skillbill.desktop.core.domain.model.ChatNewTaskContext,
    directory: String,
  ): Boolean {
    if (directory.isBlank()) return false
    val owner = Any()
    resumeTransitionOwner = owner
    resumeSourceSessionsState.value = resumeSourceSessionsState.value + entry.sessionId
    val databaseState = databaseSession.state.value
    var staged: NewTaskChat? = null
    var published = false
    val transition = scope.launch(start = CoroutineStart.LAZY) {
      try {
        val lines = entry.lines.map(::restored)
        val replay = NewTaskReplay(entry.agent.agentId, context.workingDirectory, context.baseBranch, lines, context.selectedRepositoryRoot)
        staged = open(
          UUID.randomUUID().toString(), directory, null, context.baseBranch, entry.agent,
          started = false, pendingTurnLineIndex = null, replay = replay,
          changesBaseline = null, resumedChanges = false, changesCaptureAttempted = false,
          changesAttributedPaths = null, publish = false, active = false,
        )
        staged!!.thread.replaceTranscript(lines)
        val saved = staged!!.persist(refresh = false)
        val sameDatabase = databaseState == databaseSession.state.value
        if (!saved || resumeTransitionOwner !== owner || !sameDatabase || chatState.value != null) {
          toasts.show(ToastMessage(ToastTone.Problem, "This draft could not be resumed in the current home directory. The original saved conversation is still available."))
          return@launch
        }
        repository = null
        baseBranch = context.baseBranch.orEmpty()
        agentId = entry.agent.agentId
        selectedAgent = entry.agent
        chatState.value = staged
        synchronized(cardOwners) { cardOwners[staged!!.thread] = staged!! }
        staged!!.activate()
        published = true
        refreshSaved()
        cards.revalidate(staged!!.thread, staged!!.thread.transcript.value.mapNotNull { (it as? ChatMessage.Notice)?.operationCard?.token })
        onResumePublished(this@NewTaskDraft)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (resumeTransitionOwner === owner) toasts.show(ToastMessage(ToastTone.Problem, "This draft could not be resumed in the current home directory. The original saved conversation is still available."))
      } finally {
        if (chatState.value !== staged) staged?.end()
        if (resumeTransitionOwner === owner) {
          resumeTransitionOwner = null
          resumeTransition = null
          if (!published) onResumeFailed(this@NewTaskDraft)
        }
      }
    }
    resumeTransition = transition
    transition.start()
    return true
  }

  internal fun clearResumeSources() {
    resumeSourceSessionsState.value = emptySet()
  }

  internal fun refreshSaved() {
    savedChats.refresh()
  }

  internal fun dispose() {
    scope.launch { disposeAndAwait() }
  }

  internal suspend fun disposeAndAwait() {
    contextTransitionOwner = null
    contextTransitioning = false
    contextTransitionJob?.cancelAndJoin()
    contextTransitionJob = null
    resumeTransition?.cancel()
    resumeTransition = null
    resumeTransitionOwner = null
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
    selectedRepositoryRoot: String?,
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
    publish: Boolean = true,
    active: Boolean = publish,
    databaseSessionId: Long? = (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId,
  ): NewTaskChat {
    if (publish) initialRepositoryDefaultPending = false
    val openedDatabaseSessionId = databaseSessionId
    val thread = retainedThreads.getOrPut(sessionId) { ChatThread(clock) }
    val saveLock = saveLocks.getOrPut(sessionId) { Mutex() }
    var ownedSessionId = sessionId
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
      { ownedSessionId !in invalidDrafts && retainedThreads[ownedSessionId] === thread },
      changesRepository,
      changesBaseline,
      resumedChanges,
      changesCaptureAttempted,
      changesAttributedPaths,
      previewTracker?.takeIf { publish && selectedRepositoryRoot != null && it.first == root && changesBaseline == null && !started }?.second?.also { previewTracker = null },
      diagnostics,
      { openedDatabaseSessionId != null && (databaseSession.state.value as? DatabaseSessionState.Open)?.sessionId == openedDatabaseSessionId },
      selectedRepositoryRoot,
      active,
      { contextTransitionOwner != null },
      { oldId, newId ->
        if (ownedSessionId == oldId && retainedThreads[oldId] === thread) {
          retainedThreads.remove(oldId, thread)
          saveLocks.remove(oldId, saveLock)
          retainedThreads[newId] = thread
          saveLocks[newId] = saveLock
          ownedSessionId = newId
        }
      },
      userHome,
      { contextRevision },
      { repositoryRequestGeneration },
      { current, path, session, revision, request -> onRepositoryCandidate(this, current, path, session, revision, request) },
    ).also {
      if (publish) {
        clearChatFeedback()
        chatState.value = it
        synchronized(cardOwners) { cardOwners[thread] = it }
      }
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
      else -> record(checkNotNull(current), ChatMessage.Notice(message))
    }
  }

  internal fun reportStartRefusal(current: NewTaskChat?, kind: NewTaskKind, message: String) {
    scope.launch { showStartProblem(current, kind, message) }
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

  private fun reportStandalone(current: NewTaskChat?, kind: NewTaskKind, outcome: NewTaskStartOutcome.Started, request: NewTaskStart, authority: OperationCardOrigin?) {
    val name = kind.startName()
    if (ownsChat(current)) {
      checkNotNull(current).thread.append(ChatMessage.Notice("Started $name. Follow it in Work."))
    } else {
      toasts.show(ToastMessage(ToastTone.Confirmation, "Started $name"))
    }
    val launchId = outcome.launchId
    launchId?.let { cards.remember(it, request, authority) }
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
private const val SELECT_REPOSITORY_START_TEXT = "Identify or select a repository before starting a goal, phase or operation. Ordinary chat can continue in your home directory."
private const val STALE_START_TEXT = "This start belongs to an earlier repository context. Select the intended repository and make a fresh start request."
