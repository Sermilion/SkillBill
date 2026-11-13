package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.repository.ApprovalFeedRepository
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import dev.skillbill.desktop.feature.chat.ChatApprovals
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.ChatSteeringDelivery
import dev.skillbill.desktop.feature.chat.ChatThread
import dev.skillbill.desktop.feature.chat.QueuedChatMessage
import dev.skillbill.desktop.feature.chat.STOPPED_NOTICE
import dev.skillbill.desktop.feature.chat.UnavailableApprovalFeed
import dev.skillbill.desktop.feature.chat.savedApprovalLine
import dev.skillbill.desktop.feature.chat.savedOperationCardLine
import dev.skillbill.desktop.feature.chat.show
import dev.skillbill.desktop.feature.chat.savedText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

private const val COULD_NOT_SAVE_NOTICE = "This draft could not be saved. Keep it open and try again."

/**
 * A chat about work with no execution behind it. Its coroutines are children of the window scope, so navigation keeps it.
 * [started] means the agent session exists, so the next turn resumes it. [repositoryMissing] refuses every turn.
 */
internal class NewTaskChat(
  private val chat: ExecutionChatRepository,
  private val scope: CoroutineScope,
  private val clock: Clock,
  initialSessionId: String,
  var databaseSessionId: Long?,
  val repositoryRoot: String,
  val baseBranch: String?,
  agent: ChatAgent,
  initiallyStarted: Boolean = false,
  initiallyPendingTurnLineIndex: Int? = null,
  val repositoryMissing: Boolean = false,
  replay: NewTaskReplay? = null,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val onSaved: () -> Unit = {},
  approvals: ApprovalFeedRepository = UnavailableApprovalFeed,
  retainedThread: ChatThread = ChatThread(clock),
  private val saveLock: Mutex = Mutex(),
  private val canPersist: () -> Boolean = { true },
  changesRepository: ChatChangesRepository = UnavailableChatChanges,
  initialChangesBaseline: ChatChangesBaseline? = null,
  resumedChanges: Boolean = false,
  changesCaptureAttempted: Boolean = false,
  changesAttributedPaths: Set<String>? = null,
  retainedChanges: NewTaskChatChanges? = null,
  private val diagnostics: Diagnostics,
  private val canRecover: () -> Boolean = { true },
  val selectedRepositoryRoot: String? = repositoryRoot,
  initiallyActive: Boolean = true,
  private val transitionReserved: () -> Boolean = { false },
  private val onSessionRotated: (String, String) -> Unit = { _, _ -> },
) {
  @Volatile
  var agent: ChatAgent = agent
    private set

  internal suspend fun changeModel(next: ChatAgent): Boolean {
    chat.end(sessionId)
    chat.awaitEnd(sessionId)
    val saved = saveLock.withLock {
      if (!canPersist() || !canRecover()) return@withLock false
      chat.saveNewTaskDraft(historyEntry(sessionId).copy(agent = next))
    }
    if (!saved) return false
    agent = next
    thread.agentState.value = next
    thread.clearBackgroundCommands()
    return true
  }

  internal val transitionLock = Any()
  private val transitionDispatcher = if (dispatcher == Dispatchers.Unconfined) {
    diagnostics.record("chat.unconfined_transitions")
    dispatcher
  } else dispatcher.limitedParallelism(1)
  private val finishingTurns = mutableSetOf<Job>()
  private val acceptedSaves = mutableSetOf<Job>()
  private val sessionIdState = MutableStateFlow(initialSessionId)
  val sessionIdFlow: StateFlow<String> = sessionIdState.asStateFlow()
  val sessionId: String get() = sessionIdState.value
  internal val transitionBlocked: Boolean get() = rotationInProgress || transitionReserved()

  val thread = retainedThread
  val job: Job = SupervisorJob(scope.coroutineContext[Job])
  private val approvalFlow = ChatApprovals(approvals)
  private val changesTracker = retainedChanges ?: NewTaskChatChanges(
    changesRepository, scope, dispatcher, repositoryRoot, initialChangesBaseline, resumedChanges, repositoryMissing || selectedRepositoryRoot == null,
    changesCaptureAttempted || initiallyStarted || initialChangesBaseline != null, changesAttributedPaths,
  )
  val changes: StateFlow<ChatChangesState> = changesTracker.state
  suspend fun observeChanges() = changesTracker.observe()
  val changesBaseline: ChatChangesBaseline? get() = changesTracker.baseline
  internal val changesResumed: Boolean get() = changesTracker.resumed
  internal val changesCaptureAttempted: Boolean get() = changesTracker.captureAttempted
  internal val changesAttributedPaths: Set<String>? get() = changesTracker.attributedPaths

  /** True only while the approval feed holds this window's operator registration for the draft's repository. */
  val approvalLiveState = MutableStateFlow(false)
  val approvalLive: StateFlow<Boolean> = approvalLiveState.asStateFlow()
  @Volatile
  private var turnRunning = false
  @Volatile
  private var normalReplyActive = false
  @Volatile
  private var unpromptedReplyActive = false
  @Volatile
  private var closing = false
  private var recoveryOnly = false
  @Volatile private var rotationInProgress = false
  private val steering = ChatSteeringDelivery(chat, diagnostics, thread,
    CoroutineScope(scope.coroutineContext + job + transitionDispatcher), transitionLock,
    { captured -> job.isActive && !closing && sessionId == captured && canPersist() }, ::scheduleDrain, ::persistAccepted)
  @Volatile
  var started = initiallyStarted
    private set

  /** Earlier conversation sent once with the first turn that reaches the agent; never saved. */
  @Volatile
  var pendingReplay: NewTaskReplay? = replay
    private set
  @Volatile
  private var pendingTurnLineIndex: Int? = initiallyPendingTurnLineIndex
  @Volatile
  private var unpromptedJob: Job? = null
  @Volatile
  private var pendingAcceptance: (() -> Unit)? = null
  @Volatile
  private var stoppedTurn: Job? = null
  @Volatile
  private var active = false

  init {
    thread.agentState.value = agent
    thread.agentSupportedState.value = chat.supports(agent.agentId)
    thread.showCapability(agent)
    if (initiallyActive) activate()
  }

  internal fun activate() {
    if (active || closing) return
    active = true
    observeUnprompted()
    followApprovals()
  }

  // Collecting the feed is what makes this window the repository's operator consumer; it ends with the draft's job.
  private fun followApprovals() {
    if (repositoryMissing || !active) return
    scope.launch(job + transitionDispatcher) {
      approvalFlow.follow(thread, approvalLiveState, { repositoryRoot }, { it.key.runId == sessionId }) {
        if (!closing && !save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      }
    }
  }

  /** The operator's click on one option of a pending card; it sends nothing unless the feed holds a live registration. */
  fun decideApproval(key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean, pattern: String? = null) {
    if (!approvalLiveState.value || closing) return
    scope.launch(job + transitionDispatcher) { approvalFlow.decide(thread, key, optionId, alwaysAllow, pattern) }
  }

  fun dismissApproval(key: ApprovalCardKey) {
    if (!approvalLiveState.value || closing) return
    scope.launch(job + transitionDispatcher) { approvalFlow.dismiss(thread, key) }
  }

  fun send(
    text: String,
    action: ChatSuggestedAction? = null,
    images: List<ChatImage> = emptyList(),
    onAccepted: () -> Unit = {},
    cursorMcpDependency: CursorMcpDependency = CursorMcpDependency.ProjectUserOnly,
    onRejected: (() -> Unit)? = null,
  ) = synchronized(transitionLock) {
    if ((text.isBlank() && images.isEmpty()) || repositoryMissing || closing || !active || rotationInProgress || transitionReserved()) {
      onRejected?.invoke()
      return@synchronized
    }
    thread.enqueue(QueuedChatMessage(text, action, pauseAndAct = false, images = images, cursorMcpDependency = cursorMcpDependency, onRejected = onRejected))
    onAccepted()
    drainQueue()
  }

  fun stop(): List<QueuedChatMessage> {
    if (!thread.stoppable.value || closing || rotationInProgress || transitionReserved()) return emptyList()
    val removed = synchronized(transitionLock) {
      steering.settle()
      thread.takeOrdinaryQueuedEntries()
    }
    scope.launch(job + transitionDispatcher) { interrupt() }
    return removed
  }

  fun sendNow() = synchronized(transitionLock) {
    if (recoveryOnly || !thread.stoppable.value || thread.queued.value.isEmpty() || closing || rotationInProgress || transitionReserved()) return@synchronized
    if (chat.steeringPolicy(agent.agentId) == ChatSteeringPolicy.LegacySendNow) {
      interrupt()
    } else {
      steering.send(sessionId, agent.agentId)
    }
  }

  private fun scheduleDrain() {
    scope.launch(job + transitionDispatcher) { drainQueue() }
  }

  private fun drainQueue() = synchronized(transitionLock) {
    if (recoveryOnly || closing || rotationInProgress || transitionReserved() || repositoryMissing || thread.busyState.value || turnJob != null || turnRunning) return
    val next = thread.takeQueuedMessage() ?: return
    startTurn(next.text, next.action, next.images, next.cursorMcpDependency, next.onRejected)
  }

  private fun startTurn(
    text: String,
    action: ChatSuggestedAction?,
    images: List<ChatImage>,
    cursorMcpDependency: CursorMcpDependency,
    onRejected: (() -> Unit)?,
  ) {
    thread.append(ChatMessage.Operator(text, action, images))
    pendingTurnLineIndex = snapshotLines().lastIndex
    pendingAcceptance = null
    thread.busyState.value = true
    thread.stoppableState.value = true
    turnRunning = true
    normalReplyActive = true
    val turn = scope.launch(job + transitionDispatcher, start = CoroutineStart.LAZY) {
      turnJob = coroutineContext[Job]
      try {
        runTurn(text, images, cursorMcpDependency, onRejected)
      } finally {
        finishTurn(coroutineContext.job)
      }
    }
    turnJob = turn
    turn.invokeOnCompletion {
      if (!closing && job.isActive) scope.launch(job + transitionDispatcher) {
        if (turnJob === turn) finishTurn(turn)
        drainQueue()
      }
    }
    turn.start()
  }

  @Volatile
  private var turnJob: Job? = null

  private suspend fun finishTurn(turn: Job) {
    val ownsCompletion = synchronized(transitionLock) {
      turnJob === turn && finishingTurns.add(turn)
    }
    if (!ownsCompletion) return
    val hadNormalReply = normalReplyActive
    normalReplyActive = false
    thread.stoppableState.value = unpromptedReplyActive
    pendingTurnLineIndex = null
    val stopped = turn === stoppedTurn && turn.isCancelled
    if (stopped) thread.append(ChatMessage.Notice(STOPPED_NOTICE))
    try {
      withContext(NonCancellable) {
        if (hadNormalReply && !unpromptedReplyActive) thread.endStreaming()
        if (stopped && !started) rotateSession()
        if (!save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
        changesTracker.refresh()
      }
    } finally {
      synchronized(transitionLock) {
        turnRunning = false
        thread.stoppableState.value = unpromptedReplyActive
        thread.busyState.value = unpromptedReplyActive
        turnJob = null
        finishingTurns.remove(turn)
      }
    }
  }

  private suspend fun rotateSession() {
    synchronized(transitionLock) {
      if (thread.hasDeliveryClaim() || recoveryOnly || rotationInProgress) return
      rotationInProgress = true
    }
    try {
      awaitAcceptedSaves()
      val old = sessionId
      val next = UUID.randomUUID().toString()
      val entry = historyEntry(next)
      saveLock.withLock {
        if (!canPersist() || !chat.saveNewTaskDraft(entry)) {
          thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
          return
        }
        sessionIdState.value = next
        onSessionRotated(old, next)
        chat.deleteSavedChat(old)
      }
      unpromptedJob?.cancel()
      unpromptedReplyActive = false
      thread.clearBackgroundCommands()
      if (!closing) observeUnprompted()
    } finally {
      rotationInProgress = false
    }
  }

  private fun interrupt() {
    steering.settle()
    thread.stoppableState.value = false
    thread.dropUnprompted = true
    unpromptedReplyActive = false
    thread.busyState.value = turnRunning
    turnJob?.let {
      stoppedTurn = it
      it.cancel()
    } ?: stopUnprompted()
  }

  private fun stopUnprompted() {
    unpromptedReplyActive = false
    thread.busyState.value = turnRunning
    thread.stoppableState.value = turnRunning
    val stoppedSession = sessionId
    chat.end(stoppedSession)
    thread.clearBackgroundCommands()
    scope.launch(job + transitionDispatcher) {
      try {
        thread.endStreaming()
        thread.append(ChatMessage.Notice(STOPPED_NOTICE))
        if (sessionId == stoppedSession && !save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
        if (sessionId == stoppedSession) changesTracker.refresh()
      } finally {
        if (sessionId == stoppedSession) {
          thread.busyState.value = normalReplyActive
          thread.stoppableState.value = normalReplyActive
        }
      }
      drainQueue()
    }
  }

  /** Saves the current transcript before a draft-preserving exit. */
  suspend fun flush(): Boolean = withContext(transitionDispatcher) {
    synchronized(transitionLock) {
      if (thread.deferDestructiveAction("close this draft")) return@withContext false
      closing = true
      unpromptedReplyActive = false
    }
    awaitAcceptedSaves()
    thread.clearQueuedEntries()
    val observer = unpromptedJob
    if (observer != null) observer.cancelAndJoin()
    val activeTurn = turnJob
    if (activeTurn != null) {
      activeTurn.cancel()
      activeTurn.join()
    }
    turnJob = null
    thread.endStreaming()
    pendingTurnLineIndex = null
    turnRunning = false
    thread.stoppableState.value = false
    thread.busyState.value = false
    val saved = save()
    if (!saved) {
      closing = false
      thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      observeUnprompted()
    } else acceptPendingMessage()
    saved
  }

  suspend fun end(): Boolean {
    val hasClaim = synchronized(transitionLock) {
      unpromptedReplyActive = false
      changesTracker.end()
      if (thread.hasDeliveryClaim()) {
        recoveryOnly = true
        thread.deferDestructiveAction("dispose of this draft")
        true
      } else false
    }
    if (hasClaim) steering.detach { chat.end(sessionId) }
    synchronized(transitionLock) {
      if (hasClaim && thread.hasDeliveryClaim()) {
        job.children.forEach { it.cancel() }
        thread.endStreaming()
        thread.stoppableState.value = false
        thread.busyState.value = false
        return false
      }
      if (hasClaim && thread.queued.value.isNotEmpty()) {
        recoveryOnly = false
        thread.stoppableState.value = false
        thread.busyState.value = false
        return false
      }
      thread.clearQueuedEntries()
      thread.stoppableState.value = false
      job.cancel()
      if (!hasClaim) chat.end(sessionId)
      return true
    }
  }

  fun retryDelivery(deliveryId: String) {
    scope.launch(job + transitionDispatcher) {
      synchronized(transitionLock) {
        if (!canRecover()) {
          thread.append(ChatMessage.Notice("The database session changed. Keep this follow-up for reference or discard it, then open a new chat."))
          return@launch
        }
        if (steering.recover(deliveryId, retry = true)) {
          if (recoveryOnly) {
            changesTracker.resume()
            observeUnprompted()
            followApprovals()
          }
          recoveryOnly = false
          drainQueue()
        }
      }
    }
  }

  fun discardDelivery(deliveryId: String) {
    scope.launch(job + transitionDispatcher) {
      synchronized(transitionLock) {
        if (steering.recover(deliveryId, retry = false)) {
          if (recoveryOnly && canRecover()) {
            changesTracker.resume()
            observeUnprompted()
            followApprovals()
            recoveryOnly = false
          }
          drainQueue()
        }
      }
    }
  }

  internal suspend fun persist(refresh: Boolean = true): Boolean = save(refresh)

  private fun observeUnprompted() {
    if (!active) return
    val observedSession = sessionId
    if (unpromptedJob?.isActive == true) return
    val observer = scope.launch(job + transitionDispatcher, start = CoroutineStart.LAZY) {
      chat.unprompted(observedSession).collect { event ->
        if (!closing && sessionId == observedSession && unpromptedJob === coroutineContext.job) showUnprompted(event)
      }
    }
    unpromptedJob = observer
    observer.start()
  }

  private fun acceptPendingMessage() {
    pendingAcceptance?.also { onAccepted ->
      pendingAcceptance = null
      onAccepted()
    }
  }

  private suspend fun runTurn(text: String, images: List<ChatImage>, cursorMcpDependency: CursorMcpDependency, onRejected: (() -> Unit)?) {
    changesTracker.beginTurn(started)
    if (!save()) {
      changesTracker.endTurn()
      thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      onRejected?.invoke()
      return
    }
    withContext(scope.coroutineContext.minusKey(Job)) { acceptPendingMessage() }
    var opened = false
    var seenEvent = false
    val preamble = pendingReplay?.takeIf { !started }?.let { newTaskReplayPreamble(it, repositoryRoot, baseBranch, selectedRepositoryRoot) }
    val prompt = if (preamble == null) text else "$preamble\n\n$text"
    try {
      chat.send(ChatTurnRequest(sessionId, !started, agent, ChatScope.NewTask(repositoryRoot, selectedRepositoryRoot, baseBranch), ChatPermissionMode.Act, prompt, images, cursorMcpDependency)).collect { event ->
        coroutineContext.ensureActive()
        if (!seenEvent) {
          seenEvent = true
          thread.dropUnprompted = false
        }
        val opening = !opened && event !is ChatEvent.Failed && event !is ChatEvent.Notice
        if (opening) {
          opened = true
          started = true
          pendingReplay = null
        }
        if (event is ChatEvent.ToolUse) changesTracker.recordTool(event.call)
        thread.show(agent, event)
        if (opening && !save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      }
    } finally {
      withContext(NonCancellable) { changesTracker.endTurn() }
    }
    changesTracker.refresh()
    if (!opened) onRejected?.invoke()
  }

  private suspend fun showUnprompted(event: ChatEvent) {
    if (event is ChatEvent.BackgroundTaskStarted || event is ChatEvent.BackgroundTaskEnded) {
      // A background command is not a reply, and it still runs or ends after the operator stopped one.
      thread.show(thread.agentState.value, event)
      return
    }
    if (thread.dropUnprompted) return
    if (event is ChatEvent.ApprovalRequested || event is ChatEvent.Notice) {
      // A card is not reply text: it neither opens a turn nor holds the composer busy.
      thread.show(thread.agentState.value, event)
      if (started && !save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      return
    }
    val beginsReply = event !is ChatEvent.Completed && event !is ChatEvent.Failed
    if (beginsReply) {
      unpromptedReplyActive = true
      changesTracker.beginTurn(started)
    }
    if (!normalReplyActive && unpromptedReplyActive) {
      thread.busyState.value = true
      thread.stoppableState.value = true
    }
    if (event is ChatEvent.ToolUse) changesTracker.recordTool(event.call)
    thread.show(thread.agentState.value, event)
    if (event !is ChatEvent.Completed && event !is ChatEvent.Failed) return
    if (event is ChatEvent.Completed || event is ChatEvent.Failed) {
      unpromptedReplyActive = false
      val stillActive = normalReplyActive
      thread.busyState.value = stillActive
      thread.stoppableState.value = stillActive
      withContext(NonCancellable) { changesTracker.endTurn() }
    }
    if (!save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
    changesTracker.refresh()
    drainQueue()
  }

  private fun snapshotLines(): List<ChatHistoryLine> = thread.transcript.value.mapNotNull { message ->
    when (message) {
      is ChatMessage.Notice -> message.operationCard?.let {
        ChatHistoryLine(ChatHistoryRole.OperationCard, message.text, operationCard = it)
      } ?: if (repositoryMissing && message.text.startsWith("This draft's repository no longer exists:")) null
        else ChatHistoryLine(ChatHistoryRole.Notice, message.text)
      is ChatMessage.Operator -> ChatHistoryLine(ChatHistoryRole.Operator, message.savedText(), selectedAction = message.action)
      is ChatMessage.Agent -> if (!message.streaming && (message.text.isNotBlank() || message.actions.isNotEmpty())) {
        ChatHistoryLine(ChatHistoryRole.Agent, message.text, actions = message.actions)
      } else null
      is ChatMessage.Error -> ChatHistoryLine(ChatHistoryRole.Error, message.text, message.failure)
      // Every approval request and outcome of the draft is saved, whole, with no clipping.
      is ChatMessage.Approval -> message.card.savedApprovalLine()
      is ChatMessage.OperationCard -> message.savedOperationCardLine()
      is ChatMessage.StateLine -> null
    }
  }

  private fun persistAccepted() {
    val acceptedSessionId = sessionId
    val lines = snapshotLines()
    val title = lines.firstOrNull { it.role == ChatHistoryRole.Operator }?.text.orEmpty()
    val context = ChatNewTaskContext(repositoryRoot, baseBranch, started, pendingTurnLineIndex, changesTracker.baseline, changesTracker.captureAttempted, changesAttributedPaths = changesTracker.attributedPaths, selectedRepositoryRoot = selectedRepositoryRoot)
    val entry = ChatHistoryEntry(acceptedSessionId, null, agent, clock.now(), title, lines, context)
    val save = scope.launch(transitionDispatcher, start = CoroutineStart.LAZY) {
      val saved = saveLock.withLock {
        if (canPersist()) chat.saveNewTaskDraft(entry)
        else false
      }
      if (saved) onSaved()
    }
    synchronized(transitionLock) {
      acceptedSaves += save
      save.invokeOnCompletion { synchronized(transitionLock) { acceptedSaves -= save } }
    }
    save.start()
  }

  internal suspend fun awaitAcceptedSaves() {
    while (true) {
      val pending = synchronized(transitionLock) { acceptedSaves.toList() }
      if (pending.isEmpty()) return
      pending.forEach { it.join() }
    }
  }

  internal fun deferAcceptedSave(action: () -> Unit): Boolean {
    if (synchronized(transitionLock) { acceptedSaves.all { it.isCompleted } }) return false
    scope.launch {
      awaitAcceptedSaves()
      action()
    }
    return true
  }

  private suspend fun save(refresh: Boolean = true): Boolean = saveAs(sessionId, refresh)

  private fun historyEntry(identity: String): ChatHistoryEntry {
    val lines = snapshotLines()
    val title = lines.firstOrNull { it.role == ChatHistoryRole.Operator }?.text.orEmpty()
    val context = ChatNewTaskContext(repositoryRoot, baseBranch, started, pendingTurnLineIndex, changesTracker.baseline, changesTracker.captureAttempted, changesAttributedPaths = changesTracker.attributedPaths, selectedRepositoryRoot = selectedRepositoryRoot)
    return ChatHistoryEntry(identity, null, agent, clock.now(), title, lines, context)
  }

  private suspend fun saveAs(identity: String, refresh: Boolean = true): Boolean {
    val saved = saveLock.withLock {
      if (!canPersist()) return@withLock false
      chat.saveNewTaskDraft(historyEntry(identity))
    }
    if (saved && refresh) onSaved()
    return saved
  }
}
