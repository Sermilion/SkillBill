package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ControlRejection
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.feature.chat.newtask.testDatabaseSessionController
import dev.skillbill.desktop.feature.chat.newtask.unconfinedNewTaskChats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStopTest {
  private val fake = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
  private val details = FakeWorkDetailRepository()
  private val controls = FakeWorkControlRepository()
  private val panes = ShellPanes()
  private val selection = SelectionHolder()
  private val clock = object : Clock {
    override fun now() = epoch
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
    fake.requests.clear()
    fake.saved.clear()
    fake.ended.clear()
    controls.calls.clear()
  }

  private fun TestScope.workspace(
    chatRepo: ExecutionChatRepository = fake,
    controlRepo: WorkControlRepository = controls,
  ): ChatWorkspace {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    return ChatWorkspace(chatRepo, details, controlRepo, testDatabaseSessionController(), WindowCoroutineScope(backgroundScope), clock, panes, selection, unconfinedNewTaskChats())
  }

  private fun hangingAfterOpening() {
    var turns = 0
    fake.events = {
      turns++
      if (turns == 1) flowOf(ChatEvent.Completed) else flow { awaitCancellation() }
    }
  }

  private fun cancellingRepo(): ExecutionChatRepository = object : ExecutionChatRepository by fake {
    override suspend fun saveChat(entry: ChatHistoryEntry) {
      currentCoroutineContext().ensureActive()
      fake.saveChat(entry)
    }
  }

  @Test
  fun `stop mid-reply keeps partial text, saves, and continues the same session`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    hangingAfterOpening()
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val sessionId = session.sessionId
    workspace.send(item.id, "question")
    runCurrent()
    session.appendDelta("partial")
    workspace.stop(item.id)
    runCurrent()
    val transcript = session.transcript.value
    assertTrue(transcript.any { it is ChatMessage.Agent && it.text == "partial" })
    assertEquals(ChatMessage.Notice(STOPPED_NOTICE), transcript.last())
    assertTrue(fake.saved.getValue(session.sessionId).lines.any { it.role == ChatHistoryRole.Agent && it.text == "partial" })
    assertFalse(session.busy.value)
    workspace.send(item.id, "next")
    runCurrent()
    assertEquals(sessionId, fake.requests.last().sessionId)
    assertFalse(fake.requests.last().firstTurn)
  }

  @Test
  fun `stop before output saves nothing and rotates the session id`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    fake.events = { flow { awaitCancellation() } }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val openingId = session.sessionId
    workspace.send(item.id, "first")
    runCurrent()
    workspace.stop(item.id)
    runCurrent()
    assertTrue(fake.saved.isEmpty())
    assertTrue(session.sessionId != openingId)
    workspace.send(item.id, "retry")
    runCurrent()
    assertTrue(fake.requests.last().firstTurn)
  }

  @Test
  fun `stop during clear and agent switch does not add stopped notice`() = runTest {
    val item = workItem("SKILL-469", lifecycle = Lifecycle.Paused).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    fake.events = { flow { awaitCancellation() } }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    workspace.send(item.id, "hold")
    runCurrent()
    workspace.clear(item.id)
    workspace.stop(item.id)
    runCurrent()
    assertTrue(workspace.session(item.id)!!.transcript.value.none { it is ChatMessage.Notice && it.text == STOPPED_NOTICE })
    assertEquals(CLEARED_NOTICE, (workspace.session(item.id)!!.transcript.value.last() as ChatMessage.Notice).text)

    workspace.switchAgent(item.id, "codex")
    workspace.stop(item.id)
    runCurrent()
    assertEquals("codex", workspace.session(item.id)!!.agentState.value?.agentId)
    assertTrue(workspace.session(item.id)!!.transcript.value.none { it is ChatMessage.Notice && it.text == STOPPED_NOTICE })
  }

  @Test
  fun `stop on unprompted reply ends the process and drops a late event`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    fake.events = { flowOf(ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    runCurrent()
    fake.unpromptedEvents.emit(session.sessionId to ChatEvent.TextDelta("bg"))
    runCurrent()
    workspace.stop(item.id)
    runCurrent()
    assertTrue(session.sessionId in fake.ended)
    assertFalse(session.busy.value)
    fake.unpromptedEvents.emit(session.sessionId to ChatEvent.TextDelta("late"))
    runCurrent()
    assertFalse(session.busy.value)
  }

  @Test
  fun `stop with queue restores draft order and sends nothing`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    hangingAfterOpening()
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    workspace.send(item.id, "hold")
    runCurrent()
    workspace.drafts[item.id] = "draft tail"
    val queuedImage = ChatImage(byteArrayOf(1), "image/png")
    val draftImage = ChatImage(byteArrayOf(2), "image/png")
    workspace.addPastedImage(item.id, draftImage)
    workspace.send(item.id, "queued one", listOf(queuedImage))
    workspace.send(item.id, "queued two")
    runCurrent()
    val before = fake.requests.size
    workspace.stop(item.id)
    runCurrent()
    assertEquals(before, fake.requests.size)
    assertEquals("queued one\n\nqueued two\n\ndraft tail", workspace.drafts[item.id])
    assertEquals(listOf(queuedImage, draftImage), workspace.pastedImages[item.id])
    assertTrue(workspace.session(item.id)!!.queued.value.isEmpty())
  }

  @Test
  fun `send now stops the turn and sends the whole queue after stopped notice`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    var turns = 0
    fake.events = { request ->
      turns++
      when {
        turns == 1 -> flowOf(ChatEvent.Completed)
        request.prompt.endsWith("first queued\n\nsecond queued") -> flowOf(ChatEvent.Completed)
        else -> flow { awaitCancellation() }
      }
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    workspace.send(item.id, "running")
    runCurrent()
    workspace.send(item.id, "first queued")
    workspace.send(item.id, "second queued")
    runCurrent()
    workspace.sendNow(item.id)
    runCurrent()
    val transcript = workspace.session(item.id)!!.transcript.value
    val stoppedAt = transcript.indexOfFirst { it is ChatMessage.Notice && it.text == STOPPED_NOTICE }
    val combined = "first queued\n\nsecond queued"
    val firstOperatorAfter = transcript.drop(stoppedAt + 1).indexOfFirst { it is ChatMessage.Operator && it.text == combined }
    assertTrue(stoppedAt >= 0 && firstOperatorAfter >= 0)
    assertEquals(3, fake.requests.size)
    assertTrue(fake.requests[2].prompt.endsWith(combined))
    assertTrue(workspace.session(item.id)!!.queued.value.isEmpty())
  }

  @Test
  fun `stop during pause wait relaunches once the execution stops`() = runTest {
    val item = workItem("SKILL-469")
    val header = MutableStateFlow(loaded(item))
    details.details = mapOf(item.id to header)
    fake.events = { request ->
      if (request.prompt.startsWith(ACT_PREFIX)) flow { awaitCancellation() } else flowOf(ChatEvent.Completed)
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    workspace.pauseAndAct(item.id, "fix")
    runCurrent()
    workspace.stop(item.id)
    runCurrent()
    header.value = loaded(item.copy(lifecycle = Lifecycle.Paused, running = false))
    runCurrent()
    assertEquals(listOf("pauseNow", "launch"), controls.calls.map { it.control })
  }

  @Test
  fun `stop during pause wait that times out shows timeout notice without launch`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to MutableStateFlow(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    workspace.pauseAndAct(item.id, "fix")
    runCurrent()
    workspace.stop(item.id)
    advanceTimeBy(5.minutes)
    runCurrent()
    assertEquals(ChatMessage.Notice(PAUSE_TIMEOUT_NOTICE), workspace.session(item.id)!!.transcript.value.last())
    assertEquals(listOf("pauseNow"), controls.calls.map { it.control })
  }

  @Test
  fun `stop during act turn still relaunches`() = runTest {
    val item = workItem("SKILL-469")
    val header = MutableStateFlow(loaded(item))
    details.details = mapOf(item.id to header)
    fake.events = { request ->
      if (request.prompt.startsWith(ACT_PREFIX)) flow { awaitCancellation() } else flowOf(ChatEvent.Completed)
    }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    workspace.pauseAndAct(item.id, "fix")
    runCurrent()
    header.value = loaded(item.copy(lifecycle = Lifecycle.Paused, running = false))
    runCurrent()
    workspace.stop(item.id)
    runCurrent()
    assertEquals(listOf("pauseNow", "launch"), controls.calls.map { it.control })
  }

  @Test
  fun `stop right after send clears busy through invokeOnCompletion settle`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    hangingAfterOpening()
    val workspace = workspace(cancellingRepo())
    workspace.open(item)
    runCurrent()
    workspace.send(item.id, "instant stop")
    workspace.stop(item.id)
    runCurrent()
    assertFalse(workspace.session(item.id)!!.busy.value)
    assertEquals(ChatMessage.Notice(STOPPED_NOTICE), workspace.session(item.id)!!.transcript.value.last())
    fake.events = { flowOf(ChatEvent.Completed) }
    workspace.send(item.id, "after")
    runCurrent()
    assertEquals(2, fake.requests.size)
  }

  @Test
  fun `stop then clear while save is suspended keeps busy until clear finishes`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val saveGate = CompletableDeferred<Unit>()
    var saves = 0
    val repo = object : ExecutionChatRepository by fake {
      override suspend fun saveChat(entry: ChatHistoryEntry) {
        currentCoroutineContext().ensureActive()
        saves++
        if (saves > 1) saveGate.await()
        fake.saveChat(entry)
      }
    }
    hangingAfterOpening()
    val workspace = workspace(repo)
    workspace.open(item)
    runCurrent()
    workspace.send(item.id, "hold")
    runCurrent()
    workspace.session(item.id)!!.appendDelta("part")
    workspace.stop(item.id)
    runCurrent()
    assertTrue(workspace.session(item.id)!!.busy.value)
    workspace.clear(item.id)
    saveGate.complete(Unit)
    runCurrent()
    assertFalse(workspace.session(item.id)!!.busy.value)
    assertEquals(CLEARED_NOTICE, (workspace.session(item.id)!!.transcript.value.last() as ChatMessage.Notice).text)
  }

  @Test
  fun `stop while pauseNow is suspended and refused starts no watcher`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val gate = CompletableDeferred<Unit>()
    val wrapped = object : WorkControlRepository by controls {
      override suspend fun pauseNow(item: WorkItem): ControlOutcome {
        gate.await()
        return ControlOutcome.Refused(ControlRejection.INELIGIBLE_TARGET, "no")
      }
    }
    val workspace = workspace(controlRepo = wrapped)
    workspace.open(item)
    runCurrent()
    workspace.pauseAndAct(item.id, "fix")
    runCurrent()
    workspace.stop(item.id)
    gate.complete(Unit)
    advanceTimeBy(5.minutes)
    runCurrent()
    assertTrue(workspace.session(item.id)!!.transcript.value.none { it is ChatMessage.Notice && it.text == PAUSE_TIMEOUT_NOTICE })
    assertTrue(controls.calls.isEmpty())
  }

  @Test
  fun `second pause-and-act during watcher wait relaunches only once`() = runTest {
    val item = workItem("SKILL-469")
    val header = MutableStateFlow(loaded(item))
    details.details = mapOf(item.id to header)
    fake.events = { flowOf(ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    workspace.pauseAndAct(item.id, "first")
    runCurrent()
    workspace.stop(item.id)
    runCurrent()
    workspace.pauseAndAct(item.id, "second")
    runCurrent()
    header.value = loaded(item.copy(lifecycle = Lifecycle.Paused, running = false))
    runCurrent()
    assertEquals(1, controls.calls.count { it.control == "launch" })
  }

  @Test
  fun `background completed during operator turn leaves stoppable true`() = runTest {
    val item = workItem("SKILL-469")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    hangingAfterOpening()
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    workspace.send(item.id, "question")
    runCurrent()
    fake.unpromptedEvents.emit(session.sessionId to ChatEvent.Completed)
    runCurrent()
    assertTrue(session.stoppable.value)
  }

  @Test
  fun `send now gate refuses double tap and held enter`() {
    val gate = SendNowGate()
    val t0 = Instant.fromEpochMilliseconds(0)
    assertTrue(gate.admit(t0, viaEnter = false))
    assertFalse(gate.admit(t0 + 100.milliseconds, viaEnter = false))
    assertTrue(gate.admit(t0 + 600.milliseconds, viaEnter = false))
    gate.pressed(t0 + 700.milliseconds, viaEnter = true)
    assertFalse(gate.admit(t0 + 1_300.milliseconds, viaEnter = true))
    gate.enterReleased()
    assertTrue(gate.admit(t0 + 1_900.milliseconds, viaEnter = true))
  }
}
