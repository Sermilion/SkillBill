package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.model.ChatAgent
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ApprovalRulesResult
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.toNewTaskKind
import dev.skillbill.desktop.core.domain.repository.ApprovalFeedRepository
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.pane.ChatCommands
import dev.skillbill.desktop.core.ui.usage.model.SelectedNewTaskUsageContext
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.feature.chat.newtask.NewTaskChats
import dev.skillbill.desktop.feature.chat.newtask.NewTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// How long pause-and-act waits for a running execution to stop, control call included.
private val PAUSE_WAIT = 5.minutes

internal const val CANNOT_PAUSE_NOTICE = "Not sent. The app can't pause this execution, so try again once it stops."

// The pause request is already sent when the wait runs out, so the execution may still pause later.
internal const val PAUSE_TIMEOUT_NOTICE =
  "The app asked the execution to pause, but it did not stop within 5 minutes, so this turn did not run. " +
    "If it pauses later, resume it from the detail controls."

internal const val CLEARED_NOTICE =
  "Cleared the chat. The next turn starts a new conversation with the current execution state."

/**
 * One repository's approval requests as a tab: its thread holds only approval cards, and [liveState] follows the feed's
 * operator registration for [repositoryRoot]. Its [job] ends with the tab, which ends the registration.
 */
internal class RepositoryApprovals(val repositoryRoot: String, val job: Job, val thread: ChatThread) {
  val liveState = MutableStateFlow(false)

  /** The repository's durable always-allow rules as last read; listing never decides anything. */
  val rules = MutableStateFlow<RepositoryRulesState>(RepositoryRulesState.Loading)

  /** Rule ids whose revoke call is in flight, so a repeated click is inert. */
  val revoking = MutableStateFlow<Set<String>>(emptySet())

  /** The latest revoke result or error, shown as visible status text. */
  val rulesNote = MutableStateFlow<RulesNote?>(null)
}

private fun approvalsTabId(repositoryRoot: String) = "approvals:$repositoryRoot"

/**
 * The window's chats, keyed by chat id. Opening an execution shows its first chat; [newChat] starts another one on the same
 * execution. Each finished turn saves the chat's latest lines through [ExecutionChatRepository],
 * `/resume` swaps a chat for a saved one of the same execution, and `/clear` starts it over. It is window-scoped: every coroutine is a child of the window
 * scope, and the window ends every chat through [closeAll]. Calls come from the UI thread. It mirrors [paneOpen] into
 * [ShellPanes.chatOpen], and hides itself when opening Specs clears that flag; hiding ends no chat, turn or agent process.
 */
@Inject
@SingleIn(WindowScope::class)
@ContributesBinding(WindowScope::class, boundType = ChatCommands::class)
class ChatWorkspace(
  private val chat: ExecutionChatRepository,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  private val databaseSession: DatabaseSessionController,
  windowScope: WindowCoroutineScope,
  private val clock: Clock,
  private val panes: ShellPanes,
  private val selection: SelectionHolder,
  private val newTask: NewTaskChats,
  private val approvals: ApprovalFeedRepository = UnavailableApprovalFeed,
  private val runs: RunOpener = UnavailableRunOpener,
) : ChatCommands {
  internal val cursorMcpDependencies = mutableStateMapOf<String, CursorMcpDependency>()
  private val scope = windowScope.scope
  private val approvalFlow = ChatApprovals(approvals)
  override val newTaskDraftOpen: StateFlow<Boolean> = newTask.anyChatOpen
  override val newTaskOpenChats: StateFlow<Int> = newTask.openCount
  private val selectedUsageContextState = MutableStateFlow<SelectedNewTaskUsageContext>(SelectedNewTaskUsageContext.Absent(0))
  override val selectedNewTaskUsageContext: StateFlow<SelectedNewTaskUsageContext> = selectedUsageContextState.asStateFlow()
  private val turns = ChatTurns(chat, details, clock)
  private val sessions = LinkedHashMap<String, ChatSession>()
  private val approvalTabs = LinkedHashMap<String, RepositoryApprovals>()
  private val operationCards = HashMap<String, OperationCardCoordinator>()
  private val resumeJobs = LinkedHashSet<Job>()
  private var observer: Job? = null

  /** Unsent composer text per chat, so switching tabs or hiding the pane keeps it. */
  internal val drafts = mutableStateMapOf<String, String>()

  /** Pasted images waiting for the next message per chat; in memory only, numbered by position in the list. */
  internal val pastedImages = mutableStateMapOf<String, List<ChatImage>>()

  // A paste still normalizing when its chat closes must not leave an orphan entry behind.
  internal fun addPastedImage(id: String, image: ChatImage) {
    if (id !in sessions) return
    cursorMcpDependencies[id] = CursorMcpDependency.ProjectUserOnly
    pastedImages[id] =pastedImages[id].orEmpty() + image
  }

  internal fun removePastedImage(id: String, index: Int) {
    val images = pastedImages[id] ?: return
    if (index !in images.indices) return
    val rest = images.filterIndexed { i, _ -> i != index }
    if (rest.isEmpty()) pastedImages.remove(id) else pastedImages[id] = rest
  }

  private val tabState = MutableStateFlow(ChatTabsState())
  val tabs: StateFlow<ChatTabsState> = tabState.asStateFlow()

  private val paneState = MutableStateFlow(false)
  val paneOpen: StateFlow<Boolean> = paneState.asStateFlow()

  /** The execution whose chat the pane shows, so the detail view can mark its chat button. */
  override val shownChatId: StateFlow<String?> =
    combine(paneState, tabState, ::shownId).stateIn(scope, SharingStarted.Eagerly, null)

  init {
    scope.launch { snapshotFlow { panes.chatOpen }.collect { open -> if (!open && paneState.value) setPaneOpen(false) } }
    scope.launch {
      var nextToken = 0L
      newTask.selected.collectLatest { draft ->
        val token = ++nextToken
        selectedUsageContextState.value = SelectedNewTaskUsageContext.Pending(token)
        combine(draft.chat, snapshotFlow { draft.agentId to draft.selectedAgent }) { chat, choice -> chat to choice }
          .flatMapLatest { (selectedChat, choice) ->
            val (agentId, selectedAgent) = choice
            when {
              agentId.isNullOrBlank() -> flowOf(SelectedNewTaskUsageContext.Absent(token))
              selectedChat != null && selectedChat.agent.agentId == agentId ->
                combine(selectedChat.sessionIdFlow, selectedChat.thread.busy) { sessionId, active ->
                  SelectedNewTaskUsageContext.Ready(token, selectedChat.agent, sessionId, active)
                }
              selectedChat != null -> flowOf(SelectedNewTaskUsageContext.Pending(token))
              selectedAgent?.agentId == agentId -> flowOf(SelectedNewTaskUsageContext.Ready(token, selectedAgent, null, false))
              else -> flowOf(SelectedNewTaskUsageContext.Pending(token))
            }
          }
          .collect { selectedUsageContextState.value = it }
      }
    }
  }

  /** The agents a chat can switch to. */
  val agents: List<String> = chat.agents()

  fun session(id: String): ChatSession? = sessions[id]

  internal fun now() = clock.now()

  override fun toggleChat(item: WorkItem?) {
    val shown = shownId(paneState.value && panes.chatOpen, tabState.value)
    when {
      shown != null && (item == null || shown == item.id) -> hide()
      item != null -> open(item)
      else -> setPaneOpen(true)
    }
  }

  override fun toggleChatPane() {
    if (paneState.value) hide() else setPaneOpen(true)
  }

  override fun openChat(item: WorkItem) = open(item)

  override fun runReviewAction(item: WorkItem, action: ReviewFindingsAction) {
    val instruction = reviewActionInstruction(item, action) ?: return
    actOnItemChat(item, instruction)
  }

  override fun runVerifyFixes(item: WorkItem, brief: String) = actOnItemChat(item, brief)

  override fun prefillGoalIntake(repoRoot: String, branch: String, text: String): Boolean {
    val draft = newTask.selected.value
    if (draft.chat.value != null || draft.text.isNotBlank()) return false
    draft.text = text
    draft.repository = NewTaskRepository(repoRoot, File(repoRoot).name)
    draft.baseBranch = branch
    return true
  }

  private fun actOnItemChat(item: WorkItem, instruction: String) {
    setPaneOpen(true)
    val existing = tabState.value.tabs.firstOrNull { it.executionId == item.id }
    val id = existing?.id ?: item.id
    if (existing == null) start(item, id, opening = false) else activate(id)
    pauseAndAct(id, instruction)
  }

  override fun closeChats() {
    newTask.disposeAll()
    closeExecutionChats()
  }

  override suspend fun closeChatsSafely(): Boolean = closeAll()

  /** Opens [item]'s chat, or only activates its first open chat; a new chat shows its state line, then streams the opening turn. */
  fun open(item: WorkItem) {
    setPaneOpen(true)
    val existing = tabState.value.tabs.firstOrNull { it.executionId == item.id }
    if (existing != null) activate(existing.id) else start(item, item.id, opening = true)
  }

  /**
   * Starts another chat on the active chat's execution, or on the window's selected execution when no tab is open. Only an
   * execution's first open chat streams the opening turn; a further one shows its state line and waits for the operator.
   */
  fun newChat() {
    val item = tabState.value.activeId?.let(sessions::get)?.item ?: selection.item.value ?: return
    if (tabState.value.tabs.none { it.executionId == item.id }) return open(item)
    setPaneOpen(true)
    start(item, UUID.randomUUID().toString(), opening = false)
  }

  private fun start(item: WorkItem, id: String, opening: Boolean) {
    val session = ChatSession(item, UUID.randomUUID().toString(), SupervisorJob(scope.coroutineContext[Job]), clock, id)
    sessions[id] = session
    followUnprompted(session)
    followApprovals(session)
    session.append(stateCard(item))
    session.busyState.value = true
    session.stoppableState.value = opening
    launchTurn(session) {
      val read = turns.accept(session, turns.freshRead(item)) ?: return@launchTurn
      if (opening) {
        turns.turn(session, read, openingInstruction(classify(read.header)))
      } else {
        turns.prepare(session, read)
      }
    }
    tabState.update { it.withTab(ChatTab(id, item.id, item.issueKey ?: item.title, item.kind, it.nextOrdinal(item.id))) }
    observeActive()
  }

  fun activate(id: String) {
    val previous = tabState.value.activeId
    tabState.update { it.activated(id) }
    // Leaving an idle chat summarizes it; a busy one is summarized when it is closed.
    if (previous != null && previous != tabState.value.activeId) {
      sessions[previous]?.takeUnless { it.busyState.value }?.let { chat.requestSummary(it.sessionId) }
    }
    observeActive()
  }

  fun hide() = setPaneOpen(false)

  // The one test of "which chat the pane shows", read by [toggleChat] and [shownChatId] alike.
  private fun shownId(open: Boolean, tabs: ChatTabsState): String? = tabs.activeExecutionId.takeIf { open }

  private fun setPaneOpen(value: Boolean) {
    paneState.value = value
    panes.chatOpen = value
  }

  /**
   * Ends [id]'s chat: cancelling its job ends any running turn, and the agent process it keeps between turns stops with its
   * background commands. A pending resume still runs.
   */
  fun close(id: String) {
    val approvalTab = approvalTabs.remove(id)
    if (approvalTab != null) {
      // The tab's collector held the operator registration; leaving ends it, which cancels what is still unanswered.
      approvalTab.job.cancel()
      tabState.update { it.without(id) }
      observeActive()
      return
    }
    val session = sessions.remove(id) ?: return
    drafts.remove(id)
    cursorMcpDependencies.remove(id)
    pastedImages.remove(id)
    operationCards.remove(id)
    session.job.cancel()
    chat.end(session.sessionId)
    // Read now, because a restart can rotate the session id.
    chat.requestSummary(session.sessionId)
    tabState.update { it.without(id) }
    observeActive()
  }

  /** Ends every chat, and every New task chat's agent process with its saved draft kept. Resumes are cancelled first, so a quit mid-act leaves the execution paused rather than half resumed. */
  suspend fun closeAll(): Boolean {
    if (!newTask.closeAll()) return false
    closeExecutionChats()
    return true
  }

  private fun closeExecutionChats() {
    resumeJobs.toList().forEach { it.cancel() }
    resumeJobs.clear()
    observer?.cancel()
    observer = null
    sessions.values.forEach {
      val idle = !it.busyState.value
      it.job.cancel()
      chat.end(it.sessionId)
      if (idle) chat.requestSummary(it.sessionId)
    }
    sessions.clear()
    cursorMcpDependencies.clear()
    approvalTabs.values.forEach { it.job.cancel() }
    approvalTabs.clear()
    drafts.clear()
    pastedImages.clear()
    tabState.value = ChatTabsState()
  }

  fun executeAction(id: String, reply: ChatMessage.Agent, action: ChatSuggestedAction) {
    val session = sessions[id] ?: return
    // An approval card after the reply does not move the suggested actions off their reply.
    if (session.transcript.value.lastOrNull { !it.isCard } !== reply) return
    if (reply.streaming || action !in reply.actions || !session.agentSupportedState.value || session.agentState.value == null) return
    val start = action.start
    if (start != null) {
      executeStartAction(session, action, start)
      return
    }
    when (action.mode) {
      ChatPermissionMode.ReadOnly -> send(id, action.instruction, action)
      ChatPermissionMode.Act -> pauseAndAct(id, action.instruction, action)
    }
  }

  /** Retries the start waiting on an issue prefix with [choice]. */
  fun choosePrefix(id: String, choice: String) {
    val session = sessions[id] ?: return
    val pending = session.prefixChoiceState.value ?: return
    if (session.agentState.value == null) return
    executeStartAction(session, pending.action, pending.start, choice)
  }

  private fun executeStartAction(session: ChatSession, action: ChatSuggestedAction, start: NewTaskActionKind, keyChoice: String? = null) {
    session.prefixChoiceState.value = null
    if (coordinationModeFor(session.item) == ChatPermissionMode.ReadOnly) {
      val name = session.item.issueKey ?: session.item.id
      session.append(ChatMessage.Notice("Start for $name waits until this execution is not running."))
      return
    }
    val open = databaseSession.state.value as? DatabaseSessionState.Open
    if (open == null) {
      session.append(ChatMessage.Notice("The database session is not open."))
      return
    }
    val agent = session.agentState.value ?: return
    val kind = start.toNewTaskKind()
    scope.launch {
      val request = NewTaskStart(
        repoRoot = session.item.repositoryRoot,
        requirements = if (kind in setOf(NewTaskKind.GoalRun, NewTaskKind.PlanOnly)) action.instruction else "",
        baseBranch = null,
        agentId = agent.agentId,
        profile = agent.profile,
        kind = kind,
        keyChoice = keyChoice,
        expectedDatabaseSessionId = open.sessionId,
        target = action.target,
        branch = action.branch,
        checkoutRoot = session.item.workingRoot(),
      )
      val outcome = controls.startNewTask(request)
      when (outcome) {
        is NewTaskStartOutcome.Started -> {
          session.append(ChatMessage.Notice("Started ${start.wire}."))
          val failure = outcome.awaitFailure?.invoke()
          failure?.let { session.append(ChatMessage.Notice("${start.wire} stopped. $it")) }
          outcome.launchId?.let { launchId ->
            cards(session).remember(launchId, request)
            if (failure == null) cards(session).show(session.thread, launchId)
          }
        }
        is NewTaskStartOutcome.AwaitingConfirmation -> {
          cards(session).remember(outcome.launchId, request)
          if (!cards(session).show(session.thread, outcome.launchId)) {
            session.append(ChatMessage.Notice("${start.wire} is waiting for confirmation, but its card could not be read."))
          }
        }
        is NewTaskStartOutcome.Refused -> session.append(ChatMessage.Notice(outcome.message))
        is NewTaskStartOutcome.Failed -> session.append(ChatMessage.Error(null, readProblemText(outcome.problem)))
        is NewTaskStartOutcome.NeedsKey -> {
          session.append(ChatMessage.Notice("Choose an issue key to start this run."))
          session.prefixChoiceState.value = PendingPrefixChoice(action, start, outcome.choices)
        }
      }
    }
  }

  /** Asks the agent about the execution, in the mode its state allows now; it never changes the execution. */
  fun send(id: String, text: String, images: List<ChatImage> = emptyList(), cursorMcpDependency: CursorMcpDependency = CursorMcpDependency.ProjectUserOnly) {
    enqueue(id, QueuedChatMessage(text, null, pauseAndAct = false, images = images, cursorMcpDependency = cursorMcpDependency))
  }

  private fun send(id: String, text: String, action: ChatSuggestedAction?) {
    enqueue(id, QueuedChatMessage(text, action, pauseAndAct = false))
  }

  private fun send(session: ChatSession, text: String, images: List<ChatImage>, cursorMcpDependency: CursorMcpDependency) {
    session.stoppableState.value = true
    launchTurn(session) {
      val read = turns.accept(session, turns.freshRead(session.item)) ?: return@launchTurn
      if (!turns.turn(session, read, text, images = images, cursorMcpDependency = cursorMcpDependency)) {
        restoreDraft(session.id, text, images, cursorMcpDependency)
      }
    }
  }

  /**
   * Pauses a running execution, waits for it to stop, runs [text] in act mode and then relaunches it, even when the turn fails
   * or its tab closes. The relaunch is skipped when, after the turn, the execution is already running, finished or gone.
   * On an execution that is not running it is a plain [send].
   */
  fun pauseAndAct(id: String, text: String, images: List<ChatImage> = emptyList(), cursorMcpDependency: CursorMcpDependency = CursorMcpDependency.ProjectUserOnly) {
    enqueue(id, QueuedChatMessage(text, null, pauseAndAct = true, images = images, cursorMcpDependency = cursorMcpDependency))
  }

  private fun pauseAndAct(id: String, text: String, action: ChatSuggestedAction?) {
    enqueue(id, QueuedChatMessage(text, action, pauseAndAct = true))
  }

  /** Ends the running reply and sends nothing: queued text returns to the draft, and the operator decides what to send next. */
  fun stop(id: String) {
    val session = sessions[id] ?: return
    if (!session.stoppableState.value) return
    val queued = session.queuedState.value
    if (queued.isNotEmpty()) {
      cursorMcpDependencies[id] = if (drafts[id].isNullOrBlank() && pastedImages[id].isNullOrEmpty()) {
        queued.map { it.cursorMcpDependency }.distinct().singleOrNull() ?: CursorMcpDependency.ProjectUserOnly
      } else CursorMcpDependency.ProjectUserOnly
      session.queuedState.value = emptyList()
      drafts[id] = restoredDraft(queued, drafts[id].orEmpty())
      val restoredImages = queued.flatMap { it.images }
      if (restoredImages.isNotEmpty()) pastedImages[id] = restoredImages + pastedImages[id].orEmpty()
    }
    interrupt(session)
  }

  /** Ends the running reply; the completion drain then sends all queued messages as one turn. */
  fun sendNow(id: String) {
    val session = sessions[id] ?: return
    if (!session.stoppableState.value || session.queuedState.value.isEmpty()) return
    interrupt(session)
  }

  private fun interrupt(session: ChatSession) {
    session.stoppableState.value = false
    session.thread.dropUnprompted = true
    val running = session.turnJob
    if (running != null) {
      session.stoppedTurn = running
      running.cancel()
    } else {
      // No turn collects an unprompted reply, so ending the process is this path's job.
      launchTurn(session) {
        endAgent(session)
        turns.finishStopped(session)
      }
    }
  }

  private fun pauseAndAct(session: ChatSession, text: String, images: List<ChatImage>, cursorMcpDependency: CursorMcpDependency) {
    session.stoppableState.value = true
    launchTurn(session) {
      val read = turns.accept(session, turns.freshRead(session.item)) ?: return@launchTurn
      if (coordinationModeFor(read.header) == ChatPermissionMode.Act) {
        if (!turns.turn(session, read, text, images = images, cursorMcpDependency = cursorMcpDependency)) {
          restoreDraft(session.id, text, images, cursorMcpDependency)
        }
        return@launchTurn
      }
      val deadline = clock.now() + PAUSE_WAIT
      val header = read.header
      if (!header.pauseEligible && !header.controlEligible) {
        session.append(ChatMessage.Notice(CANNOT_PAUSE_NOTICE))
        return@launchTurn
      }
      val outcome = withContext(NonCancellable) {
        if (header.pauseEligible) controls.pauseNow(header) else controls.cancel(header)
      }
      if (outcome is ControlOutcome.Refused || outcome is ControlOutcome.Failed) {
        session.append(ChatMessage.Error(null, outcome.problemDetail().orEmpty()))
        return@launchTurn
      }
      val turn = currentCoroutineContext().job
      val stopped = try {
        awaitExecutionStopped(header, deadline)
      } catch (cancelled: CancellationException) {
        if (session.stoppedTurn === turn) watchStoppedPause(session.id, header, deadline)
        throw cancelled
      }
      if (stopped == null) {
        session.append(ChatMessage.Notice(PAUSE_TIMEOUT_NOTICE))
        return@launchTurn
      }
      val stoppedRead = turns.accept(session, stopped) ?: return@launchTurn
      session.pauseWatch?.cancel()
      if (session.resumeJob?.isActive != true) resumeAfter(turn, session.id, stoppedRead.header)
      if (!turns.turn(
        session,
        stoppedRead,
        text,
        prefix = ACT_PREFIX,
        images = images,
        cursorMcpDependency = cursorMcpDependency,
      )) restoreDraft(session.id, text, images, cursorMcpDependency)
    }
  }

  internal suspend fun models(agent: ChatAgent, root: String) = chat.models(agent, root)

  internal fun switchModel(id: String, model: ChatModel?) {
    val session = sessions[id] ?: return
    val agent = session.agentState.value ?: return
    if (session.busyState.value || (agent.model == model?.id && agent.effort == null)) return
    endAgent(session)
    val selected = agent.copy(model = model?.id, effort = null)
    session.agentState.value = selected
    session.append(ChatMessage.Notice("Model changed to ${model?.label ?: "agent default"}."))
    scope.launch(session.job) { turns.save(session, selected) }
  }

  /**
   * Moves [id]'s chat to [agentId] with that agent's reasoning model, or its CLI default when the config sets none. The next
   * turn opens a new agent session with the full snapshot. Ignored while a turn runs or when [agentId] is already in use.
   */
  fun switchAgent(id: String, agentId: String) {
    val session = sessions[id] ?: return
    if (session.busyState.value || session.agentState.value?.agentId == agentId) return
    cursorMcpDependencies[id] = CursorMcpDependency.ProjectUserOnly
    session.busyState.value = true
    session.stoppableState.value = false
    launchTurn(session) {
      val agent = chat.agentFor(agentId)
      session.agentState.value = agent
      session.agentSupportedState.value = chat.supports(agentId)
      session.thread.showCapability(agent)
      endAgent(session)
      session.restart(UUID.randomUUID().toString())
      session.append(ChatMessage.Notice("Switched to ${chipText(agent)}. Its next turn starts a new conversation with the current execution state."))
    }
  }

  /**
   * Starts [id]'s chat over for `/clear`: a running turn ends, the transcript keeps only the state line, and the next turn opens
   * a new agent session on the same agent with the full snapshot. Earlier finished turns stay saved for `/resume`. Ignored once
   * the execution left the database.
   */
  fun clear(id: String) {
    val session = sessions[id] ?: return
    cursorMcpDependencies[id] = CursorMcpDependency.ProjectUserOnly
    pastedImages.remove(id)
    session.resumeChoicesState.value = null
    session.prefixChoiceState.value = null
    if (session.goneState.value) return
    session.queuedState.value = emptyList()
    val running = session.turnJob
    session.busyState.value = true
    session.stoppableState.value = false
    launchTurn(session) {
      running?.cancelAndJoin()
      endAgent(session)
      session.restart(UUID.randomUUID().toString())
      session.replaceTranscript(listOf(stateCard(session.item), ChatMessage.Notice(CLEARED_NOTICE)))
      session.agentState.value?.let { session.thread.showCapability(it) }
    }
  }

  /** Lists the agent sessions in [id]'s worktree and the app's saved chats of it, except the one it shows, for `/resume`. */
  fun showResumable(id: String) {
    val session = sessions[id] ?: return
    scope.launch(session.job) {
      session.worktreeMoveLock.withLock {
        // A removed checkout's sessions move first, so the listing at the selected root includes them.
        if (session.turnJob == null) turns.followWorktreeWhileLocked(session)
        session.resumeChoicesState.value = chat.savedChats(session.item.id, session.item.workingRoot(), session.sessionId)
      }
    }
  }

  fun dismissResume(id: String) {
    sessions[id]?.resumeChoicesState?.value = null
  }

  /**
   * Swaps [id]'s chat for [entry], an agent session in its checkout or a chat the app saved for it: a running turn ends and the
   * transcript shows its latest lines. Relocation may require a fresh session; ordinary resume continues the selected agent and
   * profile with the full snapshot. Ignored once the execution left the database or for another execution's saved chat.
   */
  fun resume(id: String, entry: ChatHistoryEntry) {
    val session = sessions[id] ?: return
    cursorMcpDependencies[id] = CursorMcpDependency.ProjectUserOnly
    session.resumeChoicesState.value = null
    session.prefixChoiceState.value = null
    if (session.goneState.value || (entry.executionId != null && entry.executionId != session.item.id)) return
    session.queuedState.value = emptyList()
    val running = session.turnJob
    session.busyState.value = true
    session.stoppableState.value = false
    launchTurn(session) {
      running?.cancelAndJoin()
      session.worktreeMoveLock.withLock {
        turns.followWorktreeWhileLocked(session)
        val lines = try {
          chat.openSavedChat(entry)
        } catch (failure: IOException) {
          session.append(ChatMessage.Error(null, failure.message ?: "Couldn't open the saved provider session."))
          return@withLock
        }
        endAgent(session)
        val root = session.item.workingRoot()
        val continueSession = session.canContinue(entry, root)
        if (continueSession) {
          val providerSessionId = if (entry.agent.agentId == "cursor") entry.appSessionId ?: entry.sessionId else entry.sessionId
          session.resumeFrom(providerSessionId, root, entry.agent)
        } else {
          session.restart(UUID.randomUUID().toString())
        }
        session.agentState.value = entry.agent
        session.agentSupportedState.value = chat.supports(entry.agent.agentId)
        val shown = if (lines.isEmpty()) "The app can't read this session's messages; the agent still has them." else "Showing its last ${lines.size} messages."
        val continuation = if (continueSession) {
          "The next turn continues it with the current execution state."
        } else if (entry.agent.agentId == "junie" && entry.executionCheckoutRoot == null) {
          "The saved checkout is unknown, so the next turn starts a fresh provider session. The saved conversation remains available."
        } else {
          "The next turn starts a fresh provider session with the current execution state."
        }
        val notice = ChatMessage.Notice(
          "Resumed the ${chipText(entry.agent)} session from ${savedAgo(entry.updatedAt, clock.now())}. $shown " +
            continuation,
        )
        session.replaceTranscript(session.resumeTranscript(stateCard(session.item), notice, lines))
        session.thread.showCapability(entry.agent)
        cards(session).revalidate(session.thread, lines.mapNotNull { it.operationCard?.token })
      }
    }
  }

  /** The Confirm, Dismiss, Adjust and option callbacks of the operation cards in chat [id], or null for an unknown chat. */
  internal fun operationCardActions(id: String): ChatOperationCardActions? =
    sessions[id]?.let { session -> cards(session).actions(session.thread) }

  private fun cards(session: ChatSession): OperationCardCoordinator =
    operationCards.getOrPut(session.id) {
      OperationCardCoordinator(controls, scope, { kind ->
        val open = databaseSession.state.value as? DatabaseSessionState.Open
        val agent = session.agentState.value
        if (open == null || agent == null) {
          null
        } else {
          NewTaskStart(
            repoRoot = session.item.repositoryRoot,
            requirements = "",
            baseBranch = null,
            agentId = agent.agentId,
            profile = agent.profile,
            kind = kind,
            keyChoice = null,
            expectedDatabaseSessionId = open.sessionId,
            checkoutRoot = session.item.workingRoot(),
          )
        }
      }, { turns.operationCardChanged(session) }, runs = runs)
    }

  private fun enqueue(id: String, message: QueuedChatMessage) {
    val session = sessions[id] ?: return
    if (session.goneState.value || (message.text.isBlank() && message.images.isEmpty())) return
    session.prefixChoiceState.value = null
    session.queuedState.update { it + message }
    drainQueue(session)
  }

  private fun restoreDraft(id: String, text: String, images: List<ChatImage>, cursorMcpDependency: CursorMcpDependency) {
    if (!drafts[id].isNullOrBlank() || !pastedImages[id].isNullOrEmpty()) return
    if (text.isBlank() && images.isEmpty()) return
    drafts[id] = text
    pastedImages[id] = images
    cursorMcpDependencies[id] = cursorMcpDependency
  }

  private fun drainQueue(session: ChatSession) {
    if (!session.job.isActive || session.busyState.value || session.turnJob != null || session.resumeJob != null || session.goneState.value) return
    val message = session.thread.takeQueuedMessage() ?: return
    if (message.action == null && cards(session).submitAdjustment(session.thread, message.text)) return
    session.busyState.value = true
    session.append(ChatMessage.Operator(message.text, message.action, message.images))
    if (message.pauseAndAct) pauseAndAct(session, message.text, message.images, message.cursorMcpDependency) else send(session, message.text, message.images, message.cursorMcpDependency)
  }

  private fun launchTurn(session: ChatSession, block: suspend () -> Unit) {
    val turn = scope.launch(session.job, start = CoroutineStart.LAZY) {
      try {
        block()
      } finally {
        settleTurn(session, coroutineContext.job)
      }
    }
    session.turnJob = turn
    turn.invokeOnCompletion {
      scope.launch(session.job) {
        settleTurn(session, turn)
        drainQueue(session)
      }
    }
    turn.start()
  }

  // Only the session's current turn clears busy, so a turn that a resume cancelled cannot unlock the composer mid-swap.
  private suspend fun settleTurn(session: ChatSession, turn: Job) {
    if (session.turnJob !== turn) return
    try {
      if (session.stoppedTurn === turn && turn.isCancelled) withContext(NonCancellable) { turns.finishStopped(session) }
    } finally {
      if (session.turnJob === turn) {
        session.turnJob = null
        session.stoppedTurn = null
        session.busyState.value = session.resumeJob?.isActive == true
        session.stoppableState.value = false
      }
    }
  }

  private suspend fun awaitExecutionStopped(header: WorkItem, deadline: Instant): DetailState? =
    withTimeoutOrNull((deadline - clock.now()).coerceAtLeast(Duration.ZERO)) {
      details.observe(header).firstOrNull {
        it.sections is DetailSections.Gone || (it.sections !is DetailSections.Pending && coordinationModeFor(it.header) == ChatPermissionMode.Act)
      }
    }

  // A window-scope job, like resumeAfter: the operator stopped the turn, but the execution still pauses and still needs its relaunch.
  private fun watchStoppedPause(id: String, header: WorkItem, deadline: Instant) {
    sessions[id]?.pauseWatch?.cancel()
    val watch = scope.launch {
      val stopped = awaitExecutionStopped(header, deadline)
      val session = sessions[id]
      when {
        stopped == null -> session?.append(ChatMessage.Notice(PAUSE_TIMEOUT_NOTICE))
        stopped.sections is DetailSections.Gone -> session?.let(turns::markGone)
        else -> {
          session?.busyState?.value = true
          resumeAfter(session?.turnJob ?: Job().apply { complete() }, id, stopped.header)
        }
      }
    }
    sessions[id]?.pauseWatch = watch
    resumeJobs += watch
    watch.invokeOnCompletion { resumeJobs -= watch }
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
        if (after != null && coordinationModeFor(after) == ChatPermissionMode.ReadOnly) {
          sessions[id]?.append(ChatMessage.Notice(ALREADY_RUNNING_NOTICE))
        } else {
          sessions[id]?.append(
            ChatMessage.Error(null, "Couldn't resume the execution: ${outcome.problemDetail()}. Resume it from the detail controls."),
          )
        }
      }
    }
    val session = sessions[id]
    session?.resumeJob = resume
    resumeJobs += resume
    resume.invokeOnCompletion {
      resumeJobs -= resume
      scope.launch {
        if (session?.resumeJob === resume) {
          session.resumeJob = null
          if (session.turnJob == null) session.busyState.value = false
          drainQueue(session)
        }
      }
    }
  }

  // True, after noting why, when the execution is already running or finished, so the app must not relaunch it.
  private fun skipRelaunch(id: String, current: WorkItem): Boolean {
    val notice = when {
      coordinationModeFor(current) == ChatPermissionMode.ReadOnly -> ALREADY_RUNNING_NOTICE
      current.lifecycle == Lifecycle.Terminal -> "The execution finished during this turn; the app did not relaunch it."
      else -> return false
    }
    sessions[id]?.append(ChatMessage.Notice(notice))
    return true
  }

  // Stops the agent process the chat kept between turns; a reply it was streaming on its own stops where it is.
  private fun endAgent(session: ChatSession) {
    chat.end(session.sessionId)
    session.endStreaming()
  }

  // Shows the replies the agent starts on its own, for whichever agent session the chat continues now.
  private fun followUnprompted(session: ChatSession) {
    scope.launch(session.job) {
      session.sessionIds.collectLatest { id ->
        chat.unprompted(id).collect {
          turns.unprompted(session, it)
          drainQueue(session)
        }
      }
    }
  }

  // Collecting the feed is what makes this window the repository's operator consumer, so it lives exactly as long as the chat.
  private fun followApprovals(session: ChatSession) {
    scope.launch(session.job) {
      approvalFlow.follow(
        thread = session.thread,
        live = session.approvalLiveState,
        repositoryRoot = { session.item.workingRoot() },
        // The chat's own process requests, and the execution's phase requests, whose run is the execution itself.
        owns = { it.key.runId == session.sessionId || it.key.runId == session.item.id },
        changed = { turns.approvalChanged(session) },
      )
    }
  }

  /** The operator's click on one option of a pending card; it sends nothing unless the feed holds a live registration. */
  /** The tab listing [repositoryRoot]'s approval requests, including runs no chat is attached to; opened once per repository. */
  fun openApprovals(repositoryRoot: String) {
    setPaneOpen(true)
    val id = approvalsTabId(repositoryRoot)
    if (id in approvalTabs) {
      refreshApprovalRules(id)
      return activate(id)
    }
    val tab = RepositoryApprovals(repositoryRoot, SupervisorJob(scope.coroutineContext[Job]), ChatThread(clock))
    approvalTabs[id] = tab
    scope.launch(tab.job) {
      // A card that settled through a new always-allow rule changes the list, so the list is read again.
      approvalFlow.follow(tab.thread, tab.liveState, { repositoryRoot }, { true }) { refreshApprovalRules(id) }
    }
    refreshApprovalRules(id)
    tabState.update { it.withTab(ChatTab(id, id, "Approvals · ${File(repositoryRoot).name}", null, approvalsRepository = repositoryRoot)) }
    observeActive()
  }

  internal fun approvalsTab(id: String): RepositoryApprovals? = approvalTabs[id]

  /** Reads the repository's durable rules into the tab. It only lists: nothing is selected, granted or revoked. */
  internal fun refreshApprovalRules(tabId: String) {
    val tab = approvalTabs[tabId] ?: return
    scope.launch(tab.job) {
      tab.rules.value = when (val result = approvals.rules(tab.repositoryRoot)) {
        is ApprovalRulesResult.Listed -> RepositoryRulesState.Listed(result.rules)
        is ApprovalRulesResult.Unavailable -> RepositoryRulesState.Unavailable(result.reason)
      }
    }
  }

  /** The operator's explicit click on one rule's Revoke. It removes authority only; a repeated click is inert. */
  internal fun revokeApprovalRule(tabId: String, ruleId: String) {
    val tab = approvalTabs[tabId] ?: return
    var started = false
    tab.revoking.update {
      started = ruleId !in it
      if (started) it + ruleId else it
    }
    if (!started) return
    scope.launch(tab.job) {
      try {
        tab.rulesNote.value = approvals.revokeRule(tab.repositoryRoot, ruleId).toNote(ruleId)
      } finally {
        tab.revoking.update { it - ruleId }
      }
      refreshApprovalRules(tabId)
    }
  }

  internal fun decideRepositoryApproval(id: String, key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean) {
    val tab = approvalTabs[id]?.takeIf { it.liveState.value } ?: return
    scope.launch(tab.job) { approvalFlow.decide(tab.thread, key, optionId, alwaysAllow) }
  }

  internal fun dismissRepositoryApproval(id: String, key: ApprovalCardKey) {
    val tab = approvalTabs[id]?.takeIf { it.liveState.value } ?: return
    scope.launch(tab.job) { approvalFlow.dismiss(tab.thread, key) }
  }

  fun decideApproval(id: String, key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean) {
    val session = sessions[id] ?: return
    if (!session.approvalLiveState.value) return
    scope.launch(session.job) { approvalFlow.decide(session.thread, key, optionId, alwaysAllow) }
  }

  fun dismissApproval(id: String, key: ApprovalCardKey) {
    val session = sessions[id] ?: return
    if (!session.approvalLiveState.value) return
    scope.launch(session.job) { approvalFlow.dismiss(session.thread, key) }
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
            // A refresh during a streaming turn leaves the live process alone; the next accept follows the removed worktree.
            if (it.turnJob == null) turns.followRoot(it)
            val title = state.header.issueKey ?: state.header.title
            tabState.update { s -> s.copy(tabs = s.tabs.map { t -> if (t.executionId == it.item.id) t.copy(title = title) else t }) }
            it.coordinationModeState.value = coordinationModeFor(state.header)
          }
        }
      }
    }
  }
}
