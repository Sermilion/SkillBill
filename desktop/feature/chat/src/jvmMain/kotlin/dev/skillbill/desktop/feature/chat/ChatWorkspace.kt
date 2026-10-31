package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// How long pause-and-act waits for a running execution to stop, control call included.
private val PAUSE_WAIT = 5.minutes

/**
 * The window's chats, one per execution id, held in memory only. It is window-scoped: every coroutine is a child of the window
 * scope, and the window ends every chat through [closeAll]. Calls come from the UI thread.
 */
@Inject
@SingleIn(WindowScope::class)
class ChatWorkspace(
  private val chat: ExecutionChatRepository,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  windowScope: WindowCoroutineScope,
  private val clock: Clock,
) {
  private val scope = windowScope.scope
  private val turns = ChatTurns(chat, details)
  private val sessions = LinkedHashMap<String, ChatSession>()
  private val resumeJobs = LinkedHashSet<Job>()
  private var observer: Job? = null

  private val tabState = MutableStateFlow(ChatTabsState())
  val tabs: StateFlow<ChatTabsState> = tabState.asStateFlow()

  private val paneState = MutableStateFlow(false)
  val paneOpen: StateFlow<Boolean> = paneState.asStateFlow()

  fun session(id: String): ChatSession? = sessions[id]

  /** Opens [item]'s chat, or only activates it when it is already open; a new chat shows its state line, then streams the opening turn. */
  fun open(item: WorkItem) {
    paneState.value = true
    if (item.id !in sessions) {
      val session = ChatSession(item, UUID.randomUUID().toString(), SupervisorJob(scope.coroutineContext[Job]))
      sessions[item.id] = session
      session.append(ChatMessage.StateLine(stateLine(item)))
      session.busyState.value = true
      scope.launch(session.job) {
        try {
          val read = turns.accept(session, turns.freshRead(item)) ?: return@launch
          turns.turn(session, read, modeFor(read.header), openingInstruction(classify(read.header)))
        } finally {
          session.busyState.value = false
        }
      }
    }
    tabState.update { it.withTab(ChatTab(item.id, item.title, item.kind)) }
    observeActive()
  }

  fun activate(id: String) {
    tabState.update { it.activated(id) }
    observeActive()
  }

  fun hide() {
    paneState.value = false
  }

  /** Ends [id]'s chat: cancelling its job ends any running turn and its process. A pending resume still runs. */
  fun close(id: String) {
    val session = sessions.remove(id) ?: return
    session.job.cancel()
    tabState.update { it.without(id) }
    observeActive()
  }

  /** Ends every chat. Resumes are cancelled first, so a quit mid-act leaves the execution paused rather than half resumed. */
  fun closeAll() {
    resumeJobs.toList().forEach { it.cancel() }
    resumeJobs.clear()
    observer?.cancel()
    observer = null
    sessions.values.forEach { it.job.cancel() }
    sessions.clear()
    tabState.value = ChatTabsState()
  }

  /** Asks the agent about the execution, in the mode its state allows now; it never changes the execution. */
  fun send(id: String, text: String) {
    val session = accept(id, text) ?: return
    launchTurn(session) {
      val read = turns.accept(session, turns.freshRead(session.item)) ?: return@launchTurn
      turns.turn(session, read, modeFor(read.header), text)
    }
  }

  /**
   * Pauses a running execution, waits for it to stop, runs [text] in act mode and then relaunches it, even when the turn fails
   * or its tab closes. On an execution that is not running it is a plain [send].
   */
  fun pauseAndAct(id: String, text: String) {
    val session = accept(id, text) ?: return
    launchTurn(session) {
      val read = turns.accept(session, turns.freshRead(session.item)) ?: return@launchTurn
      if (modeFor(read.header) == ChatPermissionMode.Act) {
        turns.turn(session, read, ChatPermissionMode.Act, text)
        return@launchTurn
      }
      val deadline = clock.now() + PAUSE_WAIT
      val header = read.header
      val outcome = when {
        header.pauseEligible -> controls.pauseNow(header)
        header.controlEligible -> controls.cancel(header)
        else -> {
          session.append(ChatMessage.Notice("This execution can't be paused from the app."))
          return@launchTurn
        }
      }
      if (outcome is ControlOutcome.Refused || outcome is ControlOutcome.Failed) {
        session.append(ChatMessage.Error(null, outcome.problemDetail().orEmpty()))
        return@launchTurn
      }
      val stopped = withTimeoutOrNull((deadline - clock.now()).coerceAtLeast(Duration.ZERO)) {
        details.observe(header).firstOrNull {
          it.sections is DetailSections.Gone || (it.sections !is DetailSections.Pending && modeFor(it.header) == ChatPermissionMode.Act)
        }
      }
      if (stopped == null) {
        session.append(ChatMessage.Notice("The execution did not stop within 5 minutes; nothing was changed."))
        return@launchTurn
      }
      val stoppedRead = turns.accept(session, stopped) ?: return@launchTurn
      resumeAfter(currentCoroutineContext().job, id, stoppedRead.header)
      turns.turn(session, stoppedRead, ChatPermissionMode.Act, text, prefix = ACT_PREFIX)
    }
  }

  // Ignores a call while a turn runs or after the execution left the database; otherwise marks the session busy at once.
  private fun accept(id: String, text: String): ChatSession? {
    val session = sessions[id] ?: return null
    if (session.busyState.value || session.goneState.value) return null
    session.busyState.value = true
    session.append(ChatMessage.Operator(text))
    return session
  }

  private fun launchTurn(session: ChatSession, block: suspend () -> Unit) {
    scope.launch(session.job) {
      try {
        block()
      } finally {
        session.busyState.value = false
      }
    }
  }

  // A window-scope job, so closing the tab does not cancel it; only closeAll does.
  private fun resumeAfter(turn: Job, id: String, header: WorkItem) {
    val resume = scope.launch {
      turn.join()
      val outcome = controls.launch(header, header.agentId)
      if (outcome is ControlOutcome.Refused || outcome is ControlOutcome.Failed) {
        sessions[id]?.append(
          ChatMessage.Error(null, "Couldn't resume the execution: ${outcome.problemDetail()}. Resume it from the detail controls."),
        )
      }
    }
    resumeJobs += resume
    resume.invokeOnCompletion { resumeJobs -= resume }
  }

  // Keeps the active session's header, mode and gone state current while its tab is shown.
  private fun observeActive() {
    observer?.cancel()
    val session = tabState.value.activeId?.let(sessions::get)
    observer = session?.let {
      scope.launch(it.job) {
        details.observe(it.item).collect { state ->
          if (state.sections is DetailSections.Gone) {
            turns.markGone(it)
          } else if (state.sections !is DetailSections.Pending) {
            it.item = state.header
            it.modeState.value = modeFor(state.header)
          }
        }
      }
    }
  }
}
