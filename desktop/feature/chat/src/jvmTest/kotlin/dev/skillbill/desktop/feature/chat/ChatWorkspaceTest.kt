package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.snapshots.Snapshot
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ControlRejection
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
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
  private val chat = FakeExecutionChatRepository()
  private val details = FakeWorkDetailRepository()
  private val controls = FakeWorkControlRepository()
  private val panes = ShellPanes()
  private val clock = object : Clock {
    override fun now() = epoch
  }

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  private fun TestScope.workspace(): ChatWorkspace {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    return ChatWorkspace(chat, details, controls, WindowCoroutineScope(backgroundScope), clock, panes)
  }

  @Test
  fun `each execution keeps its own chat, switching cancels none, and closing ends only that chat's turn`() = runTest {
    val goal = workItem("SKILL-1")
    val phase = workItem("phase-1", kind = WorkKind.Phase)
    details.details = mapOf(goal.id to flowOf(loaded(goal)), phase.id to flowOf(loaded(phase)))
    val cancelled = mutableListOf<String>()
    chat.events = { request -> flow<ChatEvent> { try { awaitCancellation() } finally { cancelled += request.scope.executionId } } }
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
    assertEquals(listOf(goal.id, phase.id), chat.requests.map { it.scope.executionId })
    assertTrue(goalSession.job.isActive && phaseSession.job.isActive)

    workspace.close(goal.id)
    runCurrent()

    assertEquals(listOf(goal.id), cancelled)
    assertNull(workspace.session(goal.id))
    assertTrue(phaseSession.job.isActive)
  }

  @Test
  fun `toggling the chat opens another execution's tab beside the active one and hides the pane for the active one without ending it`() = runTest {
    val phase = workItem("phase-1", kind = WorkKind.Phase)
    val operation = workItem("op-1", kind = WorkKind.Operation)
    details.details = mapOf(phase.id to flowOf(loaded(phase)), operation.id to flowOf(loaded(operation)))
    val cancelled = mutableListOf<String>()
    chat.events = { request -> flow<ChatEvent> { try { awaitCancellation() } finally { cancelled += request.scope.executionId } } }
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

    assertEquals(emptyList(), controls.calls)
    assertEquals(ChatPermissionMode.Act, chat.requests.last().mode)
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
      ChatMessage.Notice("The execution did not stop within 5 minutes; nothing was changed."),
      workspace.session(item.id)!!.transcript.value.last(),
    )
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
}
