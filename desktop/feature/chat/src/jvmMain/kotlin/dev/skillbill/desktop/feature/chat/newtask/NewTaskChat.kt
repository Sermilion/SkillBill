package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.ChatThread
import dev.skillbill.desktop.feature.chat.QueuedChatMessage
import dev.skillbill.desktop.feature.chat.STOPPED_NOTICE
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
  val agent: ChatAgent,
  initiallyStarted: Boolean = false,
  initiallyPendingTurnLineIndex: Int? = null,
  val repositoryMissing: Boolean = false,
  replay: NewTaskReplay? = null,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val onSaved: () -> Unit = {},
) {
  @Volatile
  var sessionId: String = initialSessionId
    private set

  val thread = ChatThread(clock)
  val job: Job = SupervisorJob(scope.coroutineContext[Job])
  private val saveLock = Mutex()
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
    observeUnprompted()
  }

  fun send(text: String, action: ChatSuggestedAction? = null, images: List<ChatImage> = emptyList(), onAccepted: () -> Unit = {}) {
    if ((text.isBlank() && images.isEmpty()) || repositoryMissing || closing) return
    thread.queuedState.update { it + QueuedChatMessage(text, action, pauseAndAct = false, images = images) }
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
    startTurn(next.text, next.action, next.images)
  }

  private fun startTurn(text: String, action: ChatSuggestedAction?, images: List<ChatImage>) {
    thread.append(ChatMessage.Operator(text, action, images))
    pendingTurnLineIndex = snapshotLines().lastIndex
    pendingAcceptance = null
    thread.busyState.value = true
    thread.stoppableState.value = true
    turnRunning = true
    val turn = scope.launch(job + dispatcher, start = CoroutineStart.LAZY) {
      turnJob = coroutineContext[Job]
      try {
        runTurn(text, images)
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
    thread.stoppableState.value = false
    thread.endStreaming()
    pendingTurnLineIndex = null
    val stopped = turn === stoppedTurn && turn.isCancelled
    if (stopped) thread.append(ChatMessage.Notice(STOPPED_NOTICE))
    try {
      withContext(NonCancellable) {
        if (stopped && !started) rotateSession()
        if (!save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      }
    } finally {
      turnRunning = false
      thread.stoppableState.value = false
      thread.busyState.value = false
      turnJob = null
    }
  }

  private suspend fun rotateSession() {
    val old = sessionId
    saveLock.withLock {
      sessionId = UUID.randomUUID().toString()
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
      it.cancel()
    } ?: stopUnprompted()
  }

  private fun stopUnprompted() {
    chat.end(sessionId)
    scope.launch(job + dispatcher) {
      try {
        thread.endStreaming()
        thread.append(ChatMessage.Notice(STOPPED_NOTICE))
        if (!save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
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
    return saved
  }

  fun end() {
    thread.queuedState.value = emptyList()
    thread.stoppableState.value = false
    job.cancel()
    chat.end(sessionId)
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

  private suspend fun runTurn(text: String, images: List<ChatImage>) {
    if (!save()) {
      thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
      return
    }
    withContext(scope.coroutineContext.minusKey(Job)) { acceptPendingMessage() }
    var opened = false
    var seenEvent = false
    val preamble = pendingReplay?.takeIf { !started }?.let { newTaskReplayPreamble(it, repositoryRoot, baseBranch) }
    val prompt = if (preamble == null) text else "$preamble\n\n$text"
    chat.send(ChatTurnRequest(sessionId, !started, agent, ChatScope.NewTask(repositoryRoot, baseBranch), ChatPermissionMode.Act, prompt, images)).collect { event ->
      coroutineContext.ensureActive()
      if (!seenEvent) {
        seenEvent = true
        thread.dropUnprompted = false
      }
      val opening = !opened && event !is ChatEvent.Failed
      if (opening) {
        opened = true
        started = true
        pendingReplay = null
      }
      thread.show(agent, event)
      if (opening && !save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
    }
  }

  private suspend fun showUnprompted(event: ChatEvent) {
    if (thread.dropUnprompted) return
    val ownTurn = !turnRunning
    if (ownTurn) {
      thread.busyState.value = true
      thread.stoppableState.value = true
    }
    thread.show(thread.agentState.value, event)
    if (event !is ChatEvent.Completed && event !is ChatEvent.Failed) return
    if (ownTurn) {
      thread.busyState.value = false
      thread.stoppableState.value = false
    }
    if (!save()) thread.append(ChatMessage.Notice(COULD_NOT_SAVE_NOTICE))
    drainQueue()
  }

  private fun snapshotLines(): List<ChatHistoryLine> = thread.transcript.value.mapNotNull { message ->
    when (message) {
      is ChatMessage.Operator -> ChatHistoryLine(ChatHistoryRole.Operator, message.savedText(), selectedAction = message.action)
      is ChatMessage.Agent -> if (!message.streaming && (message.text.isNotBlank() || message.actions.isNotEmpty())) {
        ChatHistoryLine(ChatHistoryRole.Agent, message.text, actions = message.actions)
      } else null
      is ChatMessage.Notice -> if (repositoryMissing && message.text.startsWith("This draft's repository no longer exists:")) null
        else ChatHistoryLine(ChatHistoryRole.Notice, message.text)
      is ChatMessage.Error -> ChatHistoryLine(ChatHistoryRole.Error, message.text, message.failure)
      is ChatMessage.StateLine -> null
    }
  }

  private suspend fun save(): Boolean {
    val saved = saveLock.withLock {
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
          ChatNewTaskContext(repositoryRoot, baseBranch, started, pendingTurnLineIndex),
        ),
      )
    }
    if (saved) onSaved()
    return saved
  }
}
