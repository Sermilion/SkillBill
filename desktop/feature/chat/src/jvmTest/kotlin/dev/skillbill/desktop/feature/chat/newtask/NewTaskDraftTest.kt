package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.ChatWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** A draft for tests that never send. */
internal fun testNewTaskDraft() = NewTaskDraft(FakeExecutionChatRepository(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System)

@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskDraftTest {
  private val chat = FakeExecutionChatRepository()
  private val root = createTempDirectory("new-task-repo").toString()

  private fun TestScope.draft() = NewTaskDraft(chat, WindowCoroutineScope(backgroundScope), Clock.System).apply {
    chatDispatcher = StandardTestDispatcher(testScheduler)
    repository = NewTaskRepository(root, "repo")
    baseBranch = "main"
    agentId = "claude"
    selectedAgent = this@NewTaskDraftTest.chat.agent
  }

  private fun NewTaskDraft.say(message: String) {
    text = message
    send()
  }

  @Test
  fun `a draft cannot send without a resolved agent`() = runTest {
    val draft = draft()
    draft.agentId = null
    draft.selectedAgent = null
    draft.text = "add export"

    assertFalse(draft.canSend())
    draft.send()
    assertTrue(chat.requests.isEmpty())
  }

  // Reusing ChatTurns.freshRead would mark a chat with no execution gone.
  @Test
  fun `a send opens a chat scoped to the new work and saves it as a draft`() = runTest {
    val draft = draft()
    draft.say("add export")
    runCurrent()

    val request = chat.requests.single()
    assertEquals(ChatScope.NewTask(root, "main"), request.scope)
    assertEquals(ChatPermissionMode.Act, request.mode)
    assertTrue(request.firstTurn)
    assertEquals("", draft.text)
    assertEquals(listOf<ChatMessage>(ChatMessage.Operator("add export")), draft.chat.value?.thread?.transcript?.value)
    assertEquals(root, chat.saved.getValue(request.sessionId).newTask?.repositoryRoot)
    draft.close()
  }

  // A retry that resumes would point the agent at a session it never created.
  @Test
  fun `a failed first turn retries as a first turn and output makes the next one resume`() = runTest {
    chat.events = { flowOf(ChatEvent.Failed(ChatFailure.BinaryMissing, null)) }
    val draft = draft()
    draft.say("one")
    runCurrent()
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    draft.say("two")
    runCurrent()
    draft.say("three")
    runCurrent()

    assertEquals(listOf(true, true, false), chat.requests.map { it.firstTurn })
    assertEquals(1, chat.requests.map { it.sessionId }.distinct().size)
    draft.close()
  }

  @Test
  fun `a saved draft resumes its session in a new window and discard deletes it`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val first = draft()
    first.say("add export")
    runCurrent()
    first.close()
    runCurrent()
    val sessionId = chat.requests.single().sessionId
    assertTrue(sessionId in chat.saved)

    val next = NewTaskDraft(chat, WindowCoroutineScope(backgroundScope), Clock.System)
    next.chatDispatcher = StandardTestDispatcher(testScheduler)
    next.repository = NewTaskRepository(root, "repo")
    next.refreshSaved()
    runCurrent()
    next.resume(next.saved.value.single())
    runCurrent()
    assertEquals("main", next.baseBranch)
    assertEquals(2, next.chat.value?.thread?.transcript?.value?.size)
    next.say("continue")
    runCurrent()
    assertEquals(sessionId to false, chat.requests.last().let { it.sessionId to it.firstTurn })

    next.discard()
    runCurrent()
    assertFalse(sessionId in chat.saved)
    assertEquals(emptyList(), next.saved.value)
  }

  @Test
  fun `a draft whose repository is gone refuses turns and offers only discard`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val first = draft()
    first.say("add export")
    runCurrent()
    val entry = checkNotNull(chat.saved.values.single()).let { it.copy(newTask = it.newTask?.copy(repositoryRoot = "$root/removed")) }
    first.close()

    val draft = draft()
    draft.resume(entry)
    draft.text = "again"
    assertFalse(draft.canSend())
    assertTrue(draft.chat.value?.repositoryMissing == true)
    assertTrue(draft.chat.value?.thread?.transcript?.value?.last() is ChatMessage.Notice)
    draft.close()
  }

  // An orphaned agent process per discarded draft otherwise.
  @Test
  fun `discard during a turn cancels it, ends the session, keeps the selections and the next send opens a new session`() = runTest {
    var cancelled = false
    chat.events = { flow { try { awaitCancellation() } finally { cancelled = true } } }
    val draft = draft()
    draft.say("one")
    runCurrent()
    val sessionId = chat.requests.single().sessionId

    draft.discard()
    runCurrent()

    assertTrue(cancelled)
    assertEquals(listOf(sessionId), chat.ended)
    assertNull(draft.chat.value)
    assertEquals(root, draft.repository?.root)
    assertEquals("claude", draft.agentId)

    chat.events = { flowOf(ChatEvent.Completed) }
    draft.say("two")
    runCurrent()
    assertNotEquals(sessionId, chat.requests.last().sessionId)
    draft.close()
  }

  // A window close that leaves `claude -p` running, or deletes the draft the operator expects to continue.
  @Test
  fun `closeAll ends the new-task chat, keeps its save and never lists it as a tab`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val draft = draft()
    val workspace = ChatWorkspace(
      chat, FakeWorkDetailRepository(), FakeWorkControlRepository(), WindowCoroutineScope(backgroundScope), Clock.System, ShellPanes(), SelectionHolder(), draft,
    )
    draft.say("add export")
    runCurrent()
    val sessionId = chat.requests.single().sessionId
    assertTrue(workspace.newTaskDraftOpen.value)
    assertTrue(workspace.tabs.value.tabs.isEmpty())

    workspace.closeAll()
    runCurrent()

    assertEquals(listOf(sessionId), chat.ended)
    assertFalse(workspace.newTaskDraftOpen.value)
    assertTrue(sessionId in chat.saved)
  }

  // A reply after a background command otherwise disappears or overlaps a send.
  @Test
  fun `an unprompted reply shows in the transcript and holds busy until it completes`() = runTest {
    val draft = draft()
    draft.say("start")
    runCurrent()
    val thread = checkNotNull(draft.chat.value).thread
    val sessionId = chat.requests.single().sessionId

    chat.unpromptedEvents.emit(sessionId to ChatEvent.TextDelta("done"))
    runCurrent()
    assertTrue(thread.busy.value)
    assertEquals("done", (thread.transcript.value.last() as ChatMessage.Agent).text)

    chat.unpromptedEvents.emit(sessionId to ChatEvent.Completed)
    runCurrent()
    assertFalse(thread.busy.value)
    draft.close()
  }
}
