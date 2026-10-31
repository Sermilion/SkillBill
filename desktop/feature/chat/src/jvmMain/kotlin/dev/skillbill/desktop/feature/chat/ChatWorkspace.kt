package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.pane.ChatCommands
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// How long pause-and-act waits for a running execution to stop, control call included.
private val PAUSE_WAIT = 5.minutes

/**
 * The window's chats, one per execution id, held in memory only. It is window-scoped: every coroutine is a child of the window
 * scope, and the window ends every chat through [closeAll]. Calls come from the UI thread. It mirrors [paneOpen] into
 * [ShellPanes.chatOpen], and hides itself when opening Specs clears that flag; hiding ends no chat.
 */
@Inject
@SingleIn(WindowScope::class)
@ContributesBinding(WindowScope::class, boundType = ChatCommands::class)
class ChatWorkspace(
  private val chat: ExecutionChatRepository,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  windowScope: WindowCoroutineScope,
  private val clock: Clock,
  private val panes: ShellPanes,
) : ChatCommands {
  private val scope = windowScope.scope
  private val turns = ChatTurns(chat, details)
  private val sessions = LinkedHashMap<String, ChatSession>()
  private val resumeJobs = LinkedHashSet<Job>()
  private var observer: Job? = null

  /** Unsent composer text per chat, so switching tabs or hiding the pane keeps it. */
  internal val drafts = mutableStateMapOf<String, String>()

  private val tabState = MutableStateFlow(ChatTabsState())
  val tabs: StateFlow<ChatTabsState> = tabState.asStateFlow()

  private val paneState = MutableStateFlow(false)
  val paneOpen: StateFlow<Boolean> = paneState.asStateFlow()

  override val shownChatId: StateFlow<String?> =
    combine(paneState, tabState, ::shownId).stateIn(scope, SharingStarted.Eagerly, null)

  init {
    scope.launch { snapshotFlow { panes.chatOpen }.collect { open -> if (!open && paneState.value) setPaneOpen(false) } }
  }

  /** The agents a chat can switch to. */
  val agents: List<String> = chat.agents()

  fun session(id: String): ChatSession? = sessions[id]

  override fun toggleChat(item: WorkItem?) {
    val shown = shownId(paneState.value && panes.chatOpen, tabState.value)
    when {
      shown != null && (item == null || shown == item.id) -> hide()
      item != null -> open(item)
      else -> setPaneOpen(true)
    }
  }

  override fun openChat(item: WorkItem) = open(item)

  override fun closeChats() = closeAll()

  /** Opens [item]'s chat, or only activates it when it is already open; a new chat shows its state line, then streams the opening turn. */
  fun open(item: WorkItem) {
    setPaneOpen(true)
    if (item.id !in sessions) {
      val session = ChatSession(item, UUID.randomUUID().toString(), SupervisorJob(scope.coroutineContext[Job]), clock)
      sessions[item.id] = session
      session.append(stateCard(item))
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
    tabState.update { it.withTab(ChatTab(item.id, item.issueKey ?: item.title, item.kind)) }
    observeActive()
  }

  fun activate(id: String) {
    tabState.update { it.activated(id) }
    observeActive()
  }

  fun hide() = setPaneOpen(false)

  // The one test of "which chat the pane shows", read by [toggleChat] and [shownChatId] alike.
  private fun shownId(open: Boolean, tabs: ChatTabsState): String? = tabs.activeId.takeIf { open }

  private fun setPaneOpen(value: Boolean) {
    paneState.value = value
    panes.chatOpen = value
  }

  /** Ends [id]'s chat: cancelling its job ends any running turn and its process. A pending resume still runs. */
  fun close(id: String) {
    val session = sessions.remove(id) ?: return
    drafts.remove(id)
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
    drafts.clear()
    tabState.value = ChatTabsState()
  }

  fun executeAction(id: String, reply: ChatMessage.Agent, action: ChatSuggestedAction) {
    val session = sessions[id] ?: return
    if (session.transcript.value.lastOrNull() !== reply) return
    if (reply.streaming || action !in reply.actions || !session.agentSupportedState.value || session.agentState.value == null) return
    when (action.mode) {
      ChatPermissionMode.ReadOnly -> send(id, action.instruction, action)
      ChatPermissionMode.Act -> pauseAndAct(id, action.instruction, action)
    }
  }

  /** Asks the agent about the execution, in the mode its state allows now; it never changes the execution. */
  fun send(id: String, text: String) = send(id, text, null)

  private fun send(id: String, text: String, action: ChatSuggestedAction?) {
    val session = accept(id, text, action) ?: return
    launchTurn(session) {
      val read = turns.accept(session, turns.freshRead(session.item)) ?: return@launchTurn
      turns.turn(session, read, modeFor(read.header), text)
    }
  }

  /**
   * Pauses a running execution, waits for it to stop, runs [text] in act mode and then relaunches it, even when the turn fails
   * or its tab closes. The relaunch is skipped when, after the turn, the execution is already running, finished or gone.
   * On an execution that is not running it is a plain [send].
   */
  fun pauseAndAct(id: String, text: String) = pauseAndAct(id, text, null)

  private fun pauseAndAct(id: String, text: String, action: ChatSuggestedAction?) {
    val session = accept(id, text, action) ?: return
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

  /**
   * Moves [id]'s chat to [agentId] with that agent's reasoning model, or its CLI default when the config sets none. The next
   * turn opens a new agent session with the full snapshot. Ignored while a turn runs or when [agentId] is already in use.
   */
  fun switchAgent(id: String, agentId: String) {
    val session = sessions[id] ?: return
    if (session.busyState.value || session.agentState.value?.agentId == agentId) return
    session.busyState.value = true
    launchTurn(session) {
      val agent = chat.agentFor(agentId)
      session.agentState.value = agent
      session.agentSupportedState.value = chat.supports(agentId)
      session.restart(UUID.randomUUID().toString())
      session.append(ChatMessage.Notice("Switched to ${chipText(agent)}. Its next turn starts a new conversation with the current execution state."))
    }
  }

  // Ignores a call while a turn runs or after the execution left the database; otherwise marks the session busy at once.
  private fun accept(id: String, text: String, action: ChatSuggestedAction?): ChatSession? {
    val session = sessions[id] ?: return null
    if (session.busyState.value || session.goneState.value) return null
    session.busyState.value = true
    session.append(ChatMessage.Operator(text, action))
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

  // A window-scope job, so closing the tab does not cancel it; only closeAll does. It re-reads the execution after the turn,
  // because the agent may have resumed, finished or removed it, and relaunches only one that is still stopped.
  private fun resumeAfter(turn: Job, id: String, header: WorkItem) {
    val resume = scope.launch {
      turn.join()
      val fresh = turns.freshRead(header)
      if (fresh?.sections is DetailSections.Gone) {
        sessions[id]?.let(turns::markGone)
        return@launch
      }
      val current = fresh?.header
      if (current != null && skipRelaunch(id, current)) return@launch
      val outcome = controls.launch(current ?: header, current?.agentId ?: header.agentId)
      if (outcome is ControlOutcome.Refused || outcome is ControlOutcome.Failed) {
        val after = turns.freshRead(header)?.takeIf { it.sections !is DetailSections.Gone }?.header
        if (after != null && modeFor(after) == ChatPermissionMode.ReadOnly) {
          sessions[id]?.append(ChatMessage.Notice(ALREADY_RUNNING_NOTICE))
        } else {
          sessions[id]?.append(
            ChatMessage.Error(null, "Couldn't resume the execution: ${outcome.problemDetail()}. Resume it from the detail controls."),
          )
        }
      }
    }
    resumeJobs += resume
    resume.invokeOnCompletion { resumeJobs -= resume }
  }

  // True, after noting why, when the execution is already running or finished, so the app must not relaunch it.
  private fun skipRelaunch(id: String, current: WorkItem): Boolean {
    val notice = when {
      modeFor(current) == ChatPermissionMode.ReadOnly -> ALREADY_RUNNING_NOTICE
      current.lifecycle == Lifecycle.Terminal -> "The execution finished during this turn; the app did not relaunch it."
      else -> return false
    }
    sessions[id]?.append(ChatMessage.Notice(notice))
    return true
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
            val title = state.header.issueKey ?: state.header.title
            tabState.update { s -> s.copy(tabs = s.tabs.map { t -> if (t.executionId == it.item.id) t.copy(title = title) else t }) }
            it.modeState.value = modeFor(state.header)
          }
        }
      }
    }
  }
}
