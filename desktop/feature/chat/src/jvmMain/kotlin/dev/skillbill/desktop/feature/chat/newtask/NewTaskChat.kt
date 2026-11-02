package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.ChatThread
import dev.skillbill.desktop.feature.chat.show
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/**
 * A chat about work with no execution behind it. Its coroutines are children of the window scope, so navigation keeps it.
 * [started] means the agent session exists, so the next turn resumes it. [repositoryMissing] refuses every turn.
 */
internal class NewTaskChat(
  private val chat: ExecutionChatRepository,
  private val scope: CoroutineScope,
  private val clock: Clock,
  val sessionId: String,
  var databaseSessionId: Long?,
  val repositoryRoot: String,
  val baseBranch: String?,
  val agent: ChatAgent,
  initiallyStarted: Boolean = false,
  initiallyFirstTurnEnded: Boolean = initiallyStarted,
  initiallyPendingTurnLineIndex: Int? = null,
  val repositoryMissing: Boolean = false,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  val thread = ChatThread(clock)
  val firstTurnEnded = kotlinx.coroutines.flow.MutableStateFlow(initiallyFirstTurnEnded)
  val job: Job = SupervisorJob(scope.coroutineContext[Job])
  private val saveLock = Mutex()
  @Volatile
  private var turnRunning = false
  @Volatile
  private var closing = false
  @Volatile
  private var started = initiallyStarted
  @Volatile
  private var pendingTurnLineIndex: Int? = initiallyPendingTurnLineIndex
  @Volatile
  private var unpromptedJob: Job? = null
  @Volatile
  private var pendingAcceptance: (() -> Unit)? = null

  init {
    thread.agentState.value = agent
    thread.agentSupportedState.value = chat.supports(agent.agentId)
    observeUnprompted()
  }

  fun send(text: String, action: ChatSuggestedAction? = null, onAccepted: () -> Unit = {}) {
    if (text.isBlank() || thread.busyState.value || repositoryMissing || closing) return
    thread.append(ChatMessage.Operator(text, action))
    pendingTurnLineIndex = snapshotLines().lastIndex
    pendingAcceptance = onAccepted
    thread.busyState.value = true
    turnRunning = true
    val turn = scope.launch(job + dispatcher, start = CoroutineStart.LAZY) {
      turnJob = coroutineContext[Job]
      try {
        runTurn(text)
      } finally {
        firstTurnEnded.value = true
        thread.endStreaming()
        pendingTurnLineIndex = null
        turnRunning = false
        thread.busyState.value = false
        if (!save()) thread.append(ChatMessage.Notice("This draft could not be saved. Keep it open and try again."))
        turnJob = null
      }
    }
    turnJob = turn
    turn.start()
  }

  @Volatile
  private var turnJob: Job? = null

  /** Saves the current transcript before a draft-preserving exit. */
  suspend fun flush(): Boolean {
    closing = true
    val observer = unpromptedJob
    if (observer != null) observer.cancelAndJoin()
    val activeTurn = turnJob
    if (activeTurn != null) {
      activeTurn.cancel()
      activeTurn.join()
    }
    thread.endStreaming()
    pendingTurnLineIndex = null
    turnRunning = false
    thread.busyState.value = false
    val saved = save()
    if (!saved) {
      closing = false
      thread.append(ChatMessage.Notice("This draft could not be saved. Keep it open and try again."))
      observeUnprompted()
    } else acceptPendingMessage()
    return saved
  }

  fun end() {
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

  private suspend fun runTurn(text: String) {
    if (!save()) {
      thread.append(ChatMessage.Notice("This draft could not be saved. Keep it open and try again."))
      return
    }
    withContext(scope.coroutineContext.minusKey(Job)) { acceptPendingMessage() }
    var opened = false
    chat.send(ChatTurnRequest(sessionId, !started, agent, ChatScope.NewTask(repositoryRoot, baseBranch), ChatPermissionMode.Act, text)).collect { event ->
      val opening = !opened && event !is ChatEvent.Failed
      if (opening) {
        opened = true
        started = true
      }
      thread.show(agent, event)
      // The file has to exist before any close, because a crash or a kill skips every later save.
      if (opening && !save()) thread.append(ChatMessage.Notice("This draft could not be saved. Keep it open and try again."))
    }
    thread.endStreaming()
  }

  private suspend fun showUnprompted(event: ChatEvent) {
    val ownTurn = !turnRunning
    if (ownTurn) thread.busyState.value = true
    thread.show(thread.agentState.value, event)
    if (event !is ChatEvent.Completed && event !is ChatEvent.Failed) return
    if (ownTurn) thread.busyState.value = false
    if (!save()) thread.append(ChatMessage.Notice("This draft could not be saved. Keep it open and try again."))
  }

  private fun snapshotLines(): List<ChatHistoryLine> = thread.transcript.value.mapNotNull { message ->
    when (message) {
      is ChatMessage.Operator -> ChatHistoryLine(ChatHistoryRole.Operator, message.text, selectedAction = message.action)
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
    return saveLock.withLock {
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
  }
}
