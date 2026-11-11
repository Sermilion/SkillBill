package dev.skillbill.desktop.feature.chat.newtask

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
import dev.skillbill.desktop.core.domain.model.ChatStopSettlement
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.repository.ApprovalFeedRepository
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import dev.skillbill.desktop.feature.chat.ChatApprovals
import dev.skillbill.desktop.feature.chat.ChatMessage
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
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
) {
  @Volatile
  var agent: ChatAgent = agent
    private set

  internal fun changeModel(next: ChatAgent) {
    if (thread.busy.value || closing || next == agent) return
    chat.end(sessionId)
    agent = next
    thread.agentState.value = next
    scope.launch(job + dispatcher) {
      if (!save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
    }
  }

  private val sessionIdState = MutableStateFlow(initialSessionId)
  val sessionIdFlow: StateFlow<String> = sessionIdState.asStateFlow()
  val sessionId: String get() = sessionIdState.value

  val thread = retainedThread
  val job: Job = SupervisorJob(scope.coroutineContext[Job])
  private val approvalFlow = ChatApprovals(approvals)
  private val changesTracker = NewTaskChatChanges(changesRepository, scope, dispatcher, repositoryRoot, initialChangesBaseline, resumedChanges, repositoryMissing, changesCaptureAttempted || initiallyStarted || initialChangesBaseline != null, changesAttributedPaths)
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
  private var closing = false
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

  init {
    thread.agentState.value = agent
    thread.agentSupportedState.value = chat.supports(agent.agentId)
    thread.showCapability(agent)
    observeUnprompted()
    followApprovals()
  }

  // Collecting the feed is what makes this window the repository's operator consumer; it ends with the draft's job.
  private fun followApprovals() {
    if (repositoryMissing) return
    scope.launch(job + dispatcher) {
      approvalFlow.follow(thread, approvalLiveState, { repositoryRoot }, { it.key.runId == sessionId }) {
        if (!closing && !save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      }
    }
  }

  /** The operator's click on one option of a pending card; it sends nothing unless the feed holds a live registration. */
  fun decideApproval(key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean) {
    if (!approvalLiveState.value || closing) return
    scope.launch(job + dispatcher) { approvalFlow.decide(thread, key, optionId, alwaysAllow) }
  }

  fun dismissApproval(key: ApprovalCardKey) {
    if (!approvalLiveState.value || closing) return
    scope.launch(job + dispatcher) { approvalFlow.dismiss(thread, key) }
  }

  fun send(
    text: String,
    action: ChatSuggestedAction? = null,
    images: List<ChatImage> = emptyList(),
    onAccepted: () -> Unit = {},
    cursorMcpDependency: CursorMcpDependency = CursorMcpDependency.ProjectUserOnly,
    onRejected: (() -> Unit)? = null,
  ) {
    if ((text.isBlank() && images.isEmpty()) || repositoryMissing || closing) return
    thread.queuedState.update {
      it + QueuedChatMessage(text, action, pauseAndAct = false, images = images, cursorMcpDependency = cursorMcpDependency, onRejected = onRejected)
    }
    onAccepted()
    drainQueue()
  }

  fun stop(): List<QueuedChatMessage> {
    if (!thread.stoppable.value || closing) return emptyList()
    val removed = thread.queuedState.getAndUpdate { emptyList() }
    interrupt()
    return removed
  }

  fun sendNow() {
    if (!thread.stoppable.value || thread.queued.value.isEmpty() || closing) return
    interrupt()
  }

  private fun drainQueue() {
    if (closing || repositoryMissing || thread.busyState.value || turnJob != null || turnRunning) return
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
    val turn = scope.launch(job + dispatcher, start = CoroutineStart.LAZY) {
      turnJob = coroutineContext[Job]
      try {
        runTurn(text, images, cursorMcpDependency, onRejected)
      } finally {
        finishTurn(coroutineContext.job)
      }
    }
    turnJob = turn
    turn.invokeOnCompletion {
      if (!closing && job.isActive) scope.launch(job) {
        if (turnJob === turn) finishTurn(turn)
        drainQueue()
      }
    }
    turn.start()
  }

  @Volatile
  private var turnJob: Job? = null

  private suspend fun finishTurn(turn: Job) {
    if (turnJob !== turn) return
    thread.stoppableState.value = false
    thread.endStreaming()
    pendingTurnLineIndex = null
    val stopped = turn === stoppedTurn
    if (stopped) thread.append(ChatMessage.Notice(STOPPED_NOTICE))
    try {
      withContext(NonCancellable) {
        if (stopped && !started && agent.agentId != "opencode") rotateSession()
        changesTracker.endTurn()
        if (!save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
        changesTracker.refresh()
      }
    } finally {
      turnRunning = false
      thread.stoppableState.value = false
      thread.busyState.value = false
      turnJob = null
      stoppedTurn = null
    }
  }

  private suspend fun rotateSession() {
    val old = sessionId
    saveLock.withLock {
      sessionIdState.value = UUID.randomUUID().toString()
      chat.deleteSavedChat(old)
    }
    unpromptedJob?.cancel()
    if (!closing) observeUnprompted()
  }

  private fun interrupt() {
    thread.stoppableState.value = false
    thread.dropUnprompted = true
    turnJob?.let {
      stoppedTurn = it
      val running = it
      val oldSessionId = sessionId
      scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
        if (requestStop(oldSessionId) != ChatStopSettlement.Settled) running.cancel()
        running.join()
      }
    } ?: stopUnprompted()
  }

  private fun stopUnprompted() {
    chat.end(sessionId)
    scope.launch(job + dispatcher) {
      try {
        thread.endStreaming()
        thread.append(ChatMessage.Notice(STOPPED_NOTICE))
        withContext(NonCancellable) { changesTracker.endTurn() }
        if (!save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
        changesTracker.refresh()
      } finally {
        thread.busyState.value = false
      }
      drainQueue()
    }
  }

  /** Saves the current transcript before a draft-preserving exit. */
  suspend fun flush(): Boolean {
    closing = true
    thread.queuedState.value = emptyList()
    val observer = unpromptedJob
    if (observer != null) observer.cancelAndJoin()
    val activeTurn = turnJob
    if (activeTurn != null) {
      val activeSessionId = sessionId
      if (requestStop(activeSessionId) != ChatStopSettlement.Settled) activeTurn.cancel()
      activeTurn.join()
    }
    turnJob = null
    thread.endStreaming()
    pendingTurnLineIndex = null
    turnRunning = false
    thread.stoppableState.value = false
    thread.busyState.value = false
    changesTracker.endTurn()
    val saved = save()
    if (!saved) {
      closing = false
      thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      observeUnprompted()
    } else acceptPendingMessage()
    return saved
  }

  fun end() {
    changesTracker.end()
    thread.queuedState.value = emptyList()
    thread.stoppableState.value = false
    chat.end(sessionId)
    job.cancel()
  }

  private suspend fun requestStop(sessionId: String): ChatStopSettlement = try {
    chat.stopTurn(sessionId)
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    ChatStopSettlement.CollectorCancellationRequired
  }

  internal suspend fun persist(): Boolean = save()

  private fun observeUnprompted() {
    unpromptedJob = scope.launch(job + dispatcher) { chat.unprompted(sessionId).collect { showUnprompted(it) } }
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
      thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      onRejected?.invoke()
      return
    }
    withContext(scope.coroutineContext.minusKey(Job)) { acceptPendingMessage() }
    var opened = false
    var seenEvent = false
    val preamble = pendingReplay?.takeIf { !started && agent.agentId != "opencode" }
      ?.let { newTaskReplayPreamble(it, repositoryRoot, baseBranch) }
    val prompt = if (preamble == null) text else "$preamble\n\n$text"
    chat.send(ChatTurnRequest(sessionId, !started, agent, ChatScope.NewTask(repositoryRoot, baseBranch), ChatPermissionMode.Act, prompt, images, cursorMcpDependency)).collect { event ->

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
      if (event is ChatEvent.ToolResult) changesTracker.refresh()
      thread.show(agent, event)
      if (opening && !save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
    }
    if (!opened) onRejected?.invoke()
  }

  private suspend fun showUnprompted(event: ChatEvent) {
    if (thread.dropUnprompted) return
    if (event is ChatEvent.ApprovalRequested || event is ChatEvent.Notice) {
      // A card is not reply text: it neither opens a turn nor holds the composer busy.
      thread.show(thread.agentState.value, event)
      if (started && !save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      return
    }
    val ownTurn = !turnRunning
    if (ownTurn) {
      thread.busyState.value = true
      thread.stoppableState.value = true
      changesTracker.beginTurn(started)
    }
    if (event is ChatEvent.ToolUse) changesTracker.recordTool(event.call)
    if (event is ChatEvent.ToolResult) changesTracker.refresh()
    thread.show(thread.agentState.value, event)
    if (event !is ChatEvent.Completed && event !is ChatEvent.Failed) return
    if (ownTurn) {
      thread.busyState.value = false
      thread.stoppableState.value = false
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

  private suspend fun save(): Boolean {
    val saved = saveLock.withLock {
      if (!canPersist()) return@withLock false
      val lines = snapshotLines()
      val title = lines.firstOrNull { it.role == ChatHistoryRole.Operator }?.text.orEmpty()
      chat.saveNewTaskDraft(
        ChatHistoryEntry(
          sessionId,
          null,
          agent,
          clock.now(),
          title,
          lines,
          ChatNewTaskContext(repositoryRoot, baseBranch, started, pendingTurnLineIndex, changesTracker.baseline, changesTracker.captureAttempted, changesTracker.attributedPaths),
        ),
      )
    }
    if (saved) onSaved()
    return saved
  }
}
