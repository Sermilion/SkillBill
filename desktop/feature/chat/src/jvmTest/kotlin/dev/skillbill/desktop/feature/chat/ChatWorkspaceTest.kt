package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.snapshots.Snapshot
import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ControlRejection
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.GoalWorktree
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.feature.chat.newtask.testDatabaseSessionController
import dev.skillbill.desktop.feature.chat.newtask.testNewTaskDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class ChatWorkspaceTest {
  private val chat = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
  private val details = FakeWorkDetailRepository()
  private val controls = FakeWorkControlRepository()
  private val panes = ShellPanes()
  private val selection = SelectionHolder()
  private val clock = object : Clock {
    override fun now() = epoch
  }

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  private fun TestScope.workspace(): ChatWorkspace {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    return ChatWorkspace(chat, details, controls, testDatabaseSessionController(), WindowCoroutineScope(backgroundScope), clock, panes, selection, testNewTaskDraft())
  }

  @Test
  fun `removed worktree ends the old agent session and starts the next turn at the primary root`() = runTest {
    val active = workItem("SKILL-468").copy(
      repositoryRoot = "/repo",
      goalWorktree = GoalWorktree("/repo/.skill-bill/worktrees/SKILL-468", GoalWorktreeState.Active, null, null),
    )
    val detail = MutableStateFlow(loaded(active))
    details.details = mapOf(active.id to detail)
    val workspace = workspace()
    workspace.open(active)
    runCurrent()
    val session = workspace.session(active.id)!!
    val firstSessionId = session.sessionId
    assertEquals("/repo/.skill-bill/worktrees/SKILL-468", (chat.requests.single().scope as ChatScope.Execution).repositoryRoot)

    val removed = active.copy(goalWorktree = active.goalWorktree?.copy(state = GoalWorktreeState.Removed))
    detail.value = loaded(removed)
    runCurrent()
    workspace.send(active.id, "continue")
    runCurrent()

    assertEquals(listOf(firstSessionId), chat.ended)
    assertEquals("/repo", (chat.requests.last().scope as ChatScope.Execution).repositoryRoot)
    assertTrue(chat.requests.last().firstTurn)
    assertEquals(1, session.transcript.value.filterIsInstance<ChatMessage.Notice>().count {
      it.text == "$REMOVED_WORKTREE_CHAT_NOTICE /repo."
    })
    workspace.closeAll()
  }

  @Test
  fun `review actions start a scoped act turn without an unrelated opening reply`() = runTest {
    val base = workItem("review", lifecycle = Lifecycle.Terminal, finishedAt = epoch)
    val item = base.copy(standaloneExecution = true, kind = WorkKind.Phase,
      control = base.control.copy(invocationId = "original-review", phaseId = "review",
        databaseFile = DatabaseFileIdentity("/selected database.db", "file")))
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val workspace = workspace()
    workspace.runReviewAction(item, ReviewFindingsAction.Verify)
    runCurrent()
    assertEquals(1, chat.requests.size)
    val request = chat.requests.single()
    assertEquals(ChatPermissionMode.Act, request.mode)
    assertTrue(request.prompt.contains("Do not edit source files"))
    assertTrue(request.prompt.contains("--db '/selected database.db' prepare-review --invocation-id 'original-review'"))
    assertTrue(request.prompt.contains("record-feedback"))
    workspace.closeAll()
  }

  @Test
  fun `messages queued during a reply run in order without splitting the streaming answer`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val replies = mutableListOf<Channel<ChatEvent>>()
    chat.events = {
      val reply = Channel<ChatEvent>(Channel.UNLIMITED)
      replies += reply
      flow { for (event in reply) emit(event) }
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    replies[0].send(ChatEvent.TextDelta("Before "))
    runCurrent()

    workspace.send(item.id, "first question")
    workspace.send(item.id, "second question")
    runCurrent()
    assertEquals(1, chat.requests.size)
    assertEquals(2, session.queued.value.size)
    replies[0].send(ChatEvent.TextDelta("after"))
    replies[0].send(ChatEvent.Completed)
    replies[0].close()
    runCurrent()

    assertEquals(2, chat.requests.size)
    assertTrue(chat.requests[1].prompt.endsWith("first question"))
    assertEquals("Before after", session.transcript.value.filterIsInstance<ChatMessage.Agent>().first().text)
    assertEquals(listOf("first question"), session.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.text })
    assertEquals(1, session.queued.value.size)
    replies[1].send(ChatEvent.Failed(ChatFailure.BinaryMissing, null))
    replies[1].close()
    runCurrent()

    assertEquals(3, chat.requests.size)
    assertTrue(chat.requests[2].prompt.endsWith("second question"))
    assertTrue(session.queued.value.isEmpty())
  }

  @Test
  fun `clear discards queued messages and closing never sends queued work`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    chat.events = { flow<ChatEvent> { awaitCancellation() } }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    workspace.pauseAndAct(item.id, "must not run after clear")
    workspace.clear(item.id)
    runCurrent()
    assertTrue(session.queued.value.isEmpty())
    assertEquals(1, chat.requests.size)
    assertTrue(controls.calls.isEmpty())

    workspace.send(item.id, "running")
    runCurrent()
    workspace.send(item.id, "must not run after close")
    workspace.close(item.id)
    runCurrent()
    assertEquals(2, chat.requests.size)
  }

  @Test
  fun `a background reply holds queued messages until it ends`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    chat.events = { flowOf(ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    chat.unpromptedEvents.emit(session.sessionId to ChatEvent.TextDelta("Background reply"))
    runCurrent()
    workspace.send(item.id, "next question")
    runCurrent()
    assertEquals(1, chat.requests.size)
    assertEquals(1, session.queued.value.size)

    chat.unpromptedEvents.emit(session.sessionId to ChatEvent.Completed)
    runCurrent()
    assertEquals(2, chat.requests.size)
    assertTrue(chat.requests.last().prompt.endsWith("next question"))
    assertTrue(session.queued.value.isEmpty())
    assertFalse(session.busy.value)
  }

  @Test
  fun `each execution keeps its own chat, switching cancels none, and closing ends only that chat's turn`() = runTest {
    val goal = workItem("SKILL-1")
    val phase = workItem("phase-1", kind = WorkKind.Phase)
    details.details = mapOf(goal.id to flowOf(loaded(goal)), phase.id to flowOf(loaded(phase)))
    val cancelled = mutableListOf<String>()
    chat.events = { request -> flow<ChatEvent> { try { awaitCancellation() } finally { cancelled += request.executionId } } }
    val workspace = workspace()

    workspace.open(goal)
    workspace.open(phase)
    runCurrent()
    workspace.activate(goal.id)
    runCurrent()

    val goalSession = workspace.session(goal.id)!!
    val phaseSession = workspace.session(phase.id)!!
    assertIs<ChatMessage.StateLine>(goalSession.transcript.value.first())
    assertIs<ChatMessage.StateLine>(phaseSession.transcript.value.first())
    assertEquals(listOf(goal.id, phase.id), chat.requests.map { it.executionId })
    assertTrue(goalSession.job.isActive && phaseSession.job.isActive)

    workspace.close(goal.id)
    runCurrent()

    assertEquals(listOf(goal.id), cancelled)
    assertNull(workspace.session(goal.id))
    assertTrue(phaseSession.job.isActive)
  }

  @Test
  fun `a further chat on the same execution skips the opening turn, answers on its own session, and closes alone`() = runTest {
    val goal = workItem("SKILL-1")
    details.details = mapOf(goal.id to flowOf(loaded(goal)))
    val cancelled = mutableListOf<String>()
    chat.events = { request -> flow<ChatEvent> { try { awaitCancellation() } finally { cancelled += request.sessionId } } }
    val workspace = workspace()

    workspace.open(goal)
    runCurrent()
    workspace.newChat()
    runCurrent()

    val tabs = workspace.tabs.value
    assertEquals(listOf("SKILL-1", "SKILL-1 (2)"), tabs.tabs.map { it.label })
    assertEquals(listOf(goal.id, goal.id), tabs.tabs.map { it.executionId })
    val second = tabs.tabs[1].id
    assertEquals(second, tabs.activeId)
    assertEquals(goal.id, workspace.shownChatId.value)
    val first = workspace.session(goal.id)!!
    val other = workspace.session(second)!!
    assertTrue(first.sessionId != other.sessionId)
    assertEquals(listOf(first.sessionId), chat.requests.map { it.sessionId })
    assertIs<ChatMessage.StateLine>(other.transcript.value.single())
    assertTrue(other.agent.value != null)
    assertFalse(other.busy.value)

    workspace.send(second, "what next?")
    runCurrent()
    assertEquals(listOf(first.sessionId, other.sessionId), chat.requests.map { it.sessionId })
    assertTrue(chat.requests.last().firstTurn)

    workspace.open(goal)
    assertEquals(goal.id, workspace.tabs.value.activeId)

    workspace.close(second)
    runCurrent()
    assertEquals(listOf(other.sessionId), cancelled)
    assertTrue(first.job.isActive)
  }

  @Test
  fun `the new chat button with no tab open starts the selected execution's first chat with its opening turn`() = runTest {
    val goal = workItem("SKILL-1")
    details.details = mapOf(goal.id to flowOf(loaded(goal)))
    chat.events = { flow<ChatEvent> { awaitCancellation() } }
    val workspace = workspace()

    workspace.newChat()
    assertTrue(workspace.tabs.value.tabs.isEmpty())

    selection.select(goal)
    workspace.newChat()
    runCurrent()

    assertEquals(listOf(goal.id), workspace.tabs.value.tabs.map { it.id })
    assertEquals(1, chat.requests.size)
    assertTrue(workspace.paneOpen.value)
  }

  @Test
  fun `toggling the chat opens another execution's tab beside the active one and hides the pane for the active one without ending it`() = runTest {
    val phase = workItem("phase-1", kind = WorkKind.Phase)
    val operation = workItem("op-1", kind = WorkKind.Operation)
    details.details = mapOf(phase.id to flowOf(loaded(phase)), operation.id to flowOf(loaded(operation)))
    val cancelled = mutableListOf<String>()
    chat.events = { request -> flow<ChatEvent> { try { awaitCancellation() } finally { cancelled += request.executionId } } }
    val workspace = workspace()

    workspace.open(phase)
    workspace.toggleChat(operation)
    runCurrent()

    assertEquals(listOf(phase.id, operation.id), workspace.tabs.value.tabs.map { it.executionId })
    assertEquals(operation.id, workspace.tabs.value.activeId)
    assertTrue(workspace.paneOpen.value && panes.chatOpen)

    workspace.toggleChat(operation)
    runCurrent()

    assertFalse(workspace.paneOpen.value || panes.chatOpen)
    assertIs<ChatMessage.StateLine>(workspace.session(operation.id)!!.transcript.value.first())
    assertTrue(workspace.session(operation.id)!!.job.isActive)
    assertTrue(cancelled.isEmpty())
  }

  @Test
  fun `the shown chat id follows the active tab while the pane shows and clears when the pane hides or Specs opens`() = runTest {
    val goal = workItem("SKILL-1")
    val phase = workItem("phase-1", kind = WorkKind.Phase)
    details.details = mapOf(goal.id to flowOf(loaded(goal)), phase.id to flowOf(loaded(phase)))
    chat.events = { flow<ChatEvent> { awaitCancellation() } }
    val workspace = workspace()

    workspace.open(goal)
    runCurrent()
    assertEquals(goal.id, workspace.shownChatId.value)

    workspace.toggleChat(phase)
    runCurrent()
    assertTrue(workspace.paneOpen.value)
    assertEquals(phase.id, workspace.shownChatId.value)

    workspace.toggleChat(phase)
    runCurrent()
    assertNull(workspace.shownChatId.value)

    workspace.toggleChat(phase)
    runCurrent()
    assertEquals(phase.id, workspace.shownChatId.value)
    panes.specsOpen = true
    Snapshot.sendApplyNotifications()
    runCurrent()
    assertNull(workspace.shownChatId.value)
  }

  @Test
  fun `an action sends its exact instruction once and an older reply cannot execute it again`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val action = ChatSuggestedAction("File an issue", "File an issue describing this execution's failure.", ChatPermissionMode.ReadOnly)
    chat.events = { flowOf(ChatEvent.TextDelta("I can file an issue."), ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val reply = assertIs<ChatMessage.Agent>(session.transcript.value.last())

    workspace.executeAction(item.id, reply, action)
    workspace.executeAction(item.id, reply, action)
    runCurrent()
    workspace.executeAction(item.id, reply, action)
    runCurrent()

    assertEquals(2, chat.requests.size)
    assertTrue(chat.requests.last().prompt.endsWith(action.instruction))
    assertEquals(ChatPermissionMode.ReadOnly, chat.requests.last().mode)
    assertEquals(listOf(ChatMessage.Operator(action.instruction, action)), session.transcript.value.filterIsInstance<ChatMessage.Operator>())
    assertTrue(controls.calls.isEmpty())
  }

  @Test
  fun `an act action pauses a running execution before sending its instruction and then resumes it`() = runTest {
    val item = workItem("SKILL-1")
    val header = MutableStateFlow(loaded(item))
    details.details = mapOf(item.id to header)
    val action = ChatSuggestedAction("Push with lease", "Push this execution's branch using a lease pinned to its verified remote tip.", ChatPermissionMode.Act)
    chat.events = { flowOf(ChatEvent.TextDelta("I can push."), ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val reply = assertIs<ChatMessage.Agent>(workspace.session(item.id)!!.transcript.value.last())

    workspace.executeAction(item.id, reply, action)
    runCurrent()
    assertEquals(listOf("pauseNow"), controls.calls.map { it.control })
    assertEquals(1, chat.requests.size)
    header.value = loaded(item.copy(lifecycle = Lifecycle.Paused, running = false))
    runCurrent()

    assertEquals(2, chat.requests.size)
    assertEquals(ChatPermissionMode.Act, chat.requests.last().mode)
    assertTrue(chat.requests.last().prompt.startsWith(ACT_PREFIX))
    assertTrue(chat.requests.last().prompt.endsWith(action.instruction))
    assertEquals(listOf("pauseNow", "launch"), controls.calls.map { it.control })
  }

  @Test
  fun `a turn that never started the CLI session leaves the next turn a first turn with the full snapshot`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    chat.events = { if (chat.requests.size == 1) flowOf(ChatEvent.Failed(ChatFailure.BinaryMissing, null)) else flowOf(ChatEvent.Completed) }
    val workspace = workspace()

    workspace.open(item)
    runCurrent()
    workspace.send(item.id, "installed it")
    runCurrent()

    val retry = chat.requests[1]
    assertTrue(retry.firstTurn)
    assertTrue(retry.prompt.startsWith("Execution context:"), retry.prompt)
  }

  @Test
  fun `pause and act pauses a running execution, acts once it stopped, and resumes it even when the tab closes mid-turn`() = runTest {
    val item = workItem("SKILL-1")
    val header = MutableStateFlow(loaded(item))
    details.details = mapOf(item.id to header)
    chat.events = { request -> if (request.mode == ChatPermissionMode.Act) flow<ChatEvent> { awaitCancellation() } else flowOf(ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.pauseAndAct(item.id, "fix the test")
    runCurrent()
    assertEquals(listOf("pauseNow"), controls.calls.map { it.control })
    header.value = loaded(item.copy(lifecycle = Lifecycle.Paused, running = false))
    runCurrent()

    val act = chat.requests.last()
    assertEquals(ChatPermissionMode.Act, act.mode)
    assertTrue(act.prompt.startsWith(ACT_PREFIX), act.prompt)
    assertTrue(act.prompt.contains("do not resume, relaunch or continue it yourself"), act.prompt)
    val fresh = item.copy(lifecycle = Lifecycle.Paused, running = false, agentId = "codex")
    header.value = loaded(fresh)
    runCurrent()
    workspace.close(item.id)
    runCurrent()
    assertEquals(listOf("pauseNow", "launch"), controls.calls.map { it.control })
    assertEquals(fresh, controls.calls.last().item)
    assertEquals("codex", controls.calls.last().agentId)
  }

  @Test
  fun `pause and act does not relaunch an execution the agent resumed during the turn`() = runTest {
    val item = workItem("SKILL-1")
    val header = MutableStateFlow(loaded(item))
    details.details = mapOf(item.id to header)
    val done = CompletableDeferred<Unit>()
    chat.events = { request ->
      if (request.mode == ChatPermissionMode.Act) {
        flow { done.await(); emit(ChatEvent.Completed) }
      } else {
        flowOf(ChatEvent.Completed)
      }
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.pauseAndAct(item.id, "fix the test and carry on")
    runCurrent()
    header.value = loaded(item.copy(lifecycle = Lifecycle.Paused, running = false))
    runCurrent()
    header.value = loaded(item.copy(lifecycle = Lifecycle.Active, running = true))
    runCurrent()
    done.complete(Unit)
    runCurrent()

    assertEquals(listOf("pauseNow"), controls.calls.map { it.control })
    val transcript = workspace.session(item.id)!!.transcript.value
    assertTrue(transcript.none { it is ChatMessage.Error }, transcript.toString())
    assertEquals(ChatMessage.Notice(ALREADY_RUNNING_NOTICE), transcript.last())
  }

  @Test
  fun `pause and act does not relaunch an execution that finished during the turn`() = runTest {
    val item = workItem("SKILL-1")
    val header = MutableStateFlow(loaded(item))
    details.details = mapOf(item.id to header)
    val done = CompletableDeferred<Unit>()
    chat.events = { request ->
      if (request.mode == ChatPermissionMode.Act) {
        flow { done.await(); emit(ChatEvent.Completed) }
      } else {
        flowOf(ChatEvent.Completed)
      }
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.pauseAndAct(item.id, "finish it")
    runCurrent()
    header.value = loaded(item.copy(lifecycle = Lifecycle.Paused, running = false))
    runCurrent()
    header.value = loaded(item.copy(lifecycle = Lifecycle.Terminal, running = false))
    runCurrent()
    done.complete(Unit)
    runCurrent()

    assertEquals(listOf("pauseNow"), controls.calls.map { it.control })
    assertTrue(workspace.session(item.id)!!.transcript.value.none { it is ChatMessage.Error })
  }

  @Test
  fun `pause and act on a stopped execution acts without any control call`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Blocked).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.pauseAndAct(item.id, "unblock it")
    runCurrent()

    assertEquals(ChatPermissionMode.Act, chat.requests.last().mode)
  }

  @Test
  fun `pause and act on a running execution the app cannot control says the message was not sent`() = runTest {
    val item = workItem("SKILL-1").copy(controlEligible = false, pauseEligible = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.pauseAndAct(item.id, "fix it")
    runCurrent()

    assertEquals(ChatMessage.Notice(CANNOT_PAUSE_NOTICE), workspace.session(item.id)!!.transcript.value.last())
    assertEquals(1, chat.requests.size)
    assertTrue(controls.calls.isEmpty())
  }

  @Test
  fun `a refused pause runs no turn and no launch`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    controls.outcome = ControlOutcome.Refused(ControlRejection.INELIGIBLE_TARGET, "not now")
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.pauseAndAct(item.id, "fix it")
    runCurrent()

    assertEquals(ChatMessage.Error(null, "not now"), workspace.session(item.id)!!.transcript.value.last())
    assertEquals(1, chat.requests.size)
    assertEquals(listOf("pauseNow"), controls.calls.map { it.control })
  }

  @Test
  fun `pause and act that waits out the 5 minutes without a stop neither acts nor relaunches`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to MutableStateFlow(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.pauseAndAct(item.id, "fix the test")
    advanceTimeBy(5.minutes)
    runCurrent()

    assertEquals(
      ChatMessage.Notice(PAUSE_TIMEOUT_NOTICE),
      workspace.session(item.id)!!.transcript.value.last(),
    )
    assertEquals(1, chat.requests.size)
    assertEquals(listOf("pauseNow"), controls.calls.map { it.control })
  }

  @Test
  fun `pause and act on an execution removed while it waits for the stop neither acts nor relaunches`() = runTest {
    val item = workItem("SKILL-1")
    val header = MutableStateFlow(loaded(item))
    details.details = mapOf(item.id to header)
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.pauseAndAct(item.id, "fix the test")
    runCurrent()
    header.value = DetailState(item, DetailSections.Gone)
    runCurrent()

    val session = workspace.session(item.id)!!
    assertTrue(session.gone.value)
    assertEquals(ChatMessage.Notice(GONE_NOTICE), session.transcript.value.last())
    assertEquals(1, chat.requests.size)
    assertEquals(listOf("pauseNow"), controls.calls.map { it.control })
  }

  @Test
  fun `an execution gone from the database gets a notice and refuses later sends`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    details.details = mapOf(item.id to flowOf(DetailState(item, DetailSections.Gone)))

    workspace.send(item.id, "still there?")
    runCurrent()
    workspace.send(item.id, "hello?")
    runCurrent()

    val transcript = workspace.session(item.id)!!.transcript.value
    assertEquals(ChatMessage.Notice(GONE_NOTICE), transcript.last())
    assertTrue(transcript.none { it == ChatMessage.Operator("hello?") })
    assertEquals(1, chat.requests.size)
  }

  // A switch that kept the old session id would resume a Claude session in Codex, or send Codex only a diff it never saw the base of.
  @Test
  fun `switching agents resolves the new agent and opens a new session with the full snapshot`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    chat.agentFor = { ChatAgent(it, "gpt-5", "high") }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val opening = chat.requests.single()

    workspace.switchAgent(item.id, "codex")
    runCurrent()
    workspace.send(item.id, "what failed?")
    runCurrent()

    assertEquals(listOf("codex"), chat.agentForCalls)
    assertEquals(listOf("claude", "codex"), workspace.agents)
    val next = chat.requests.last()
    assertEquals(ChatAgent("codex", "gpt-5", "high"), next.agent)
    assertTrue(next.firstTurn)
    assertTrue(next.sessionId != opening.sessionId && next.sessionId == session.sessionId)
    assertEquals(opening.prompt.removeSuffix(openingInstruction(classify(item))) + "what failed?", next.prompt)
    assertTrue(session.transcript.value.any { it is ChatMessage.Notice && "codex · gpt-5 · high" in it.text })
  }

  // A chat that is not saved, or a resume that starts a new agent session, loses what the operator was doing; a resume that
  // leaves the opening turn running would stream its reply into the restored chat.
  @Test
  fun `a finished turn saves the chat, and resuming it in a new window ends the running turn and continues the saved session`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    chat.events = { request ->
      flowOf(ChatEvent.TextDelta(if (request.firstTurn) "Blocked on CI." else "The lint job failed."), ChatEvent.Completed)
    }
    val first = workspace()
    first.open(item)
    runCurrent()
    first.send(item.id, "why blocked?")
    runCurrent()
    val savedSession = first.session(item.id)!!.sessionId
    val saved = chat.saved.getValue(savedSession)
    assertEquals(
      listOf(
        ChatHistoryLine(ChatHistoryRole.Agent, "Blocked on CI."),
        ChatHistoryLine(ChatHistoryRole.Operator, "why blocked?"),
        ChatHistoryLine(ChatHistoryRole.Agent, "The lint job failed."),
      ),
      saved.lines,
    )
    first.closeAll()

    chat.agent = ChatAgent("codex", null, null)
    val cancelled = mutableListOf<String>()
    chat.events = { request ->
      if (request.sessionId == savedSession) flowOf(ChatEvent.TextDelta("Rerun it."), ChatEvent.Completed)
      else flow { try { awaitCancellation() } finally { cancelled += request.sessionId } }
    }
    val second = workspace()
    second.open(item)
    runCurrent()
    val session = second.session(item.id)!!
    second.showResumable(item.id)
    runCurrent()
    assertEquals(listOf(saved), session.resumeChoices.value)

    second.resume(item.id, saved)
    runCurrent()

    assertEquals(1, cancelled.size)
    assertNull(session.resumeChoices.value)
    assertFalse(session.busy.value)
    assertEquals(ChatAgent("claude", null, null), session.agent.value)
    assertEquals(
      listOf("Blocked on CI.", "why blocked?", "The lint job failed."),
      session.transcript.value.mapNotNull { (it as? ChatMessage.Agent)?.text ?: (it as? ChatMessage.Operator)?.text },
    )

    second.send(item.id, "what next?")
    runCurrent()

    val resumed = chat.requests.last()
    assertEquals(savedSession, resumed.sessionId)
    assertFalse(resumed.firstTurn)
    assertEquals("claude", resumed.agent.agentId)
    assertTrue("execution.title" in resumed.prompt && resumed.prompt.endsWith("what next?"))
  }

  // A clear that kept the session id would continue the old conversation; one that left the turn running would stream its
  // reply into the cleared chat.
  @Test
  fun `clearing ends the running turn, keeps only the state line, and opens a new session on the same agent`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val cancelled = mutableListOf<String>()
    chat.events = { request ->
      if (request.firstTurn && request.prompt.endsWith("what next?")) flowOf(ChatEvent.TextDelta("Rerun it."), ChatEvent.Completed)
      else flow { try { awaitCancellation() } finally { cancelled += request.sessionId } }
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val opening = chat.requests.single()
    val agent = session.agent.value

    workspace.clear(item.id)
    runCurrent()

    assertEquals(listOf(opening.sessionId), cancelled)
    assertFalse(session.busy.value)
    assertEquals(agent, session.agent.value)
    val transcript = session.transcript.value
    assertIs<ChatMessage.StateLine>(transcript.first())
    assertEquals(ChatMessage.Notice(CLEARED_NOTICE), transcript.drop(1).single())

    workspace.send(item.id, "what next?")
    runCurrent()

    val next = chat.requests.last()
    assertTrue(next.firstTurn)
    assertTrue(next.sessionId != opening.sessionId && next.sessionId == session.sessionId)
    assertTrue("execution.title" in next.prompt)
  }

  // Hiding the pane used to look like it killed a background build; it must keep the agent process, and the reply the agent
  // sends when the build ends must reach the chat. Closing the tab must stop the process, or its commands run on unseen.
  @Test
  fun `hiding keeps the agent process, its own reply streams into the chat and is saved, and closing the tab ends the process`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    chat.events = { flowOf(ChatEvent.TextDelta("Build started."), ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!

    workspace.hide()
    chat.unpromptedEvents.emit(session.sessionId to ChatEvent.TextDelta("Build passed."))
    runCurrent()

    assertTrue(session.busy.value)
    assertTrue(chat.ended.isEmpty())
    chat.unpromptedEvents.emit(session.sessionId to ChatEvent.Completed)
    runCurrent()

    assertFalse(session.busy.value)
    val replies = session.transcript.value.filterIsInstance<ChatMessage.Agent>()
    assertEquals(listOf("Build started.", "Build passed."), replies.map { it.text })
    assertTrue(replies.none { it.streaming })
    assertEquals(
      listOf(ChatHistoryLine(ChatHistoryRole.Agent, "Build started."), ChatHistoryLine(ChatHistoryRole.Agent, "Build passed.")),
      chat.saved.getValue(session.sessionId).lines,
    )

    workspace.close(item.id)

    assertEquals(listOf(session.sessionId), chat.ended)
  }

  @Test
  fun `start action on running execution refuses without pause send or startNewTask`() = runTest {
    val item = workItem("SKILL-1")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val action = ChatSuggestedAction(
      "Monitor",
      "Run monitor on this PR.",
      ChatPermissionMode.Act,
      NewTaskActionKind.Monitor,
      "https://github.com/org/repo/pull/1",
    )
    chat.events = {
      flowOf(ChatEvent.TextDelta("Ready."), ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed)
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val reply = assertIs<ChatMessage.Agent>(session.transcript.value.last())

    workspace.executeAction(item.id, reply, action)
    runCurrent()

    assertTrue(controls.calls.isEmpty())
    assertTrue(controls.newTaskRequests.isEmpty())
    assertTrue(session.transcript.value.filterIsInstance<ChatMessage.Operator>().isEmpty())
    val notice = assertIs<ChatMessage.Notice>(session.transcript.value.last())
    assertTrue(notice.text.contains("SKILL-1"))
    assertTrue(notice.text.contains("not running"))
  }

  @Test
  fun `start action on stopped execution calls startNewTask in that worktree`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(running = false, repositoryRoot = "/work/execution-tree")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    controls.newTaskOutcome = NewTaskStartOutcome.Started("monitor", NewTaskKind.Monitor)
    val action = ChatSuggestedAction(
      "Monitor",
      "Run monitor.",
      ChatPermissionMode.Act,
      NewTaskActionKind.Monitor,
      target = "https://github.com/org/repo/pull/2",
    )
    chat.events = {
      flowOf(ChatEvent.TextDelta("Ready."), ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed)
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val reply = assertIs<ChatMessage.Agent>(session.transcript.value.last())

    workspace.executeAction(item.id, reply, action)
    runCurrent()

    assertTrue(controls.calls.isEmpty())
    assertTrue(session.transcript.value.filterIsInstance<ChatMessage.Operator>().isEmpty())
    assertIs<ChatMessage.Notice>(session.transcript.value.last())
    assertEquals(1, controls.newTaskRequests.size)
    with(controls.newTaskRequests.single()) {
      assertEquals("/work/execution-tree", repoRoot)
      assertEquals(NewTaskKind.Monitor, kind)
      assertEquals("https://github.com/org/repo/pull/2", target)
      assertEquals("claude", agentId)
      assertEquals(1L, expectedDatabaseSessionId)
    }
  }

  @Test
  fun `start action that needs a prefix offers the choices and retries with the chosen one`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    controls.newTaskOutcome = NewTaskStartOutcome.NeedsKey(listOf("SKILL", "OPS"))
    val action = ChatSuggestedAction("Run phase:goal", "Build the thing.", ChatPermissionMode.Act, NewTaskActionKind.Goal)
    chat.events = { flowOf(ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val reply = assertIs<ChatMessage.Agent>(session.transcript.value.last())

    workspace.executeAction(item.id, reply, action)
    runCurrent()

    assertEquals(listOf("SKILL", "OPS"), session.prefixChoice.value?.choices)
    assertEquals(null, controls.newTaskRequests.single().keyChoice)

    controls.newTaskOutcome = NewTaskStartOutcome.Started("OPS-1", NewTaskKind.GoalRun)
    workspace.choosePrefix(item.id, "OPS")
    runCurrent()

    assertEquals(null, session.prefixChoice.value)
    with(controls.newTaskRequests.last()) {
      assertEquals("OPS", keyChoice)
      assertEquals(NewTaskKind.GoalRun, kind)
      assertEquals("Build the thing.", requirements)
    }
  }

  @Test
  fun `start action refuses when database session is not open`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val action = ChatSuggestedAction("Monitor", "Run monitor.", ChatPermissionMode.Act, NewTaskActionKind.Monitor, null)
    chat.events = { flowOf(ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed) }
    val closedDb = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Opening(null)).asStateFlow()
      override fun reopen() = Unit
    }
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val workspace = ChatWorkspace(
      chat, details, controls, closedDb, WindowCoroutineScope(backgroundScope), clock, panes, selection, testNewTaskDraft(),
    )
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val reply = assertIs<ChatMessage.Agent>(session.transcript.value.last())

    workspace.executeAction(item.id, reply, action)
    runCurrent()

    assertTrue(controls.calls.isEmpty())
    assertTrue(controls.newTaskRequests.isEmpty())
    assertTrue(session.transcript.value.filterIsInstance<ChatMessage.Operator>().isEmpty())
    assertIs<ChatMessage.Notice>(session.transcript.value.last())
  }
}

private val ChatTurnRequest.executionId: String get() = (scope as ChatScope.Execution).executionId
