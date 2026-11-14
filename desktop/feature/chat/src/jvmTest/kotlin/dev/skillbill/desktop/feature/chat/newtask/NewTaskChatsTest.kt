package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

/** A window's open chats for tests that never send. */
internal fun unconfinedNewTaskChats() = NewTaskChats(
  FakeExecutionChatRepository(), FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
  WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
)

/** A window's open chats on the test scheduler. */
internal fun TestScope.testNewTaskChats(
  chat: ExecutionChatRepository = FakeExecutionChatRepository(),
  controls: WorkControlRepository = FakeWorkControlRepository(),
  toasts: ToastQueue = ToastQueue(),
): NewTaskChats = NewTaskChats(chat, controls, testDatabaseSessionController(), toasts, WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
  chatDispatcher = StandardTestDispatcher(testScheduler)
}

@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskChatsTest {
  private val root = createTempDirectory("new-task-chats").toString()

  // Selecting a chat that stops or leaks another chat's stream, a lost unread flag, or a new chat that drops the context.
  @Test
  fun `selecting another chat keeps the first one streaming and flags its new reply`() = runTest {
    val chat = FakeExecutionChatRepository()
    val reply = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { flow { for (event in reply) emit(event) } }
    val chats = testNewTaskChats(chat)
    val agent = ChatAgent("claude", null, null)
    val a = chats.selected.value.apply {
      repository = NewTaskRepository(root, "repo")
      baseBranch = "main"
      agentId = "claude"
      selectedAgent = agent
      text = "build export"
      send()
    }
    runCurrent()
    assertEquals(NewTaskChatStatus.Replying, chats.status(a).first())
    val sessionId = chat.requests.single().sessionId

    val b = chats.newChat()
    assertSame(b, chats.selected.value)
    assertEquals(emptyList(), chat.summaryRequests)
    assertEquals(listOf(a, b), chats.open.value)
    assertEquals(NewTaskRepository(root, "repo"), b.repository)
    assertEquals("main", b.baseBranch)
    assertEquals("claude", b.agentId)
    assertEquals(agent, b.selectedAgent)
    assertEquals("", b.text)

    reply.send(ChatEvent.TextDelta("done"))
    reply.send(ChatEvent.Completed)
    reply.close()
    runCurrent()

    assertFalse(sessionId in chat.ended)
    assertEquals("done", checkNotNull(a.chat.value).thread.transcript.value.filterIsInstance<ChatMessage.Agent>().single().text)
    assertEquals(NewTaskChatStatus.NewReply, chats.status(a).first())
    assertEquals(NewTaskChatStatus.Idle, chats.status(b).first())

    chats.select(a)
    assertEquals(NewTaskChatStatus.Idle, chats.status(a).first())
    a.close()
  }

  // A close that loses the partial reply or the save, a stream a declined close stops, or a Reopen that cannot find the chat.
  @Test
  fun `closing a replying chat asks first, keeps its save and reopens it from the toast`() = runTest {
    val chat = FakeExecutionChatRepository()
    val reply = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { flow { for (event in reply) emit(event) } }
    val toasts = ToastQueue()
    val chats = testNewTaskChats(chat, toasts = toasts)
    val a = chats.selected.value.ready()
    a.text = "build export"
    a.send()
    reply.send(ChatEvent.TextDelta("partial"))
    runCurrent()
    val sessionId = chat.requests.single().sessionId
    val b = chats.newChat()
    chats.select(a)

    chats.requestClose(a)
    runCurrent()
    assertSame(a, chats.pendingClose.value)
    chats.keepOpen()
    runCurrent()
    assertNull(chats.pendingClose.value)
    assertEquals(NewTaskChatStatus.Replying, chats.status(a).first())
    assertFalse(sessionId in chat.ended)

    chats.requestClose(a)
    chats.confirmClose()
    runCurrent()
    assertTrue(chat.saved.getValue(sessionId).lines.any { it.text == "partial" })
    assertEquals(listOf(b), chats.open.value)
    assertSame(b, chats.selected.value)
    assertEquals(listOf(sessionId), chat.summaryRequests.distinct())
    val reopen = toasts.host.toasts.single().data.actions.single()
    assertEquals("Reopen", reopen.label)

    reopen.onClick()
    runCurrent()
    assertEquals(listOf(b), chats.open.value)
    assertEquals(sessionId, b.chat.value?.sessionId)
  }

  // An empty open set, or a replacement chat that forgets the repository, branch or agent.
  @Test
  fun `closing the last chat leaves an empty chat with the same context and no toast`() = runTest {
    val toasts = ToastQueue()
    val chats = testNewTaskChats(toasts = toasts)
    val only = chats.selected.value.ready()

    chats.requestClose(only)
    runCurrent()

    val replacement = chats.open.value.single()
    assertNotSame(only, replacement)
    assertSame(replacement, chats.selected.value)
    assertEquals(NewTaskRepository(root, "repo"), replacement.repository)
    assertEquals("main", replacement.baseBranch)
    assertEquals("claude", replacement.agentId)
    assertEquals(only.selectedAgent, replacement.selectedAgent)
    assertTrue(toasts.host.toasts.isEmpty())
  }

  // Resume ending another chat (the old close() call), loading a chat twice, or a stray tab after a failed load.
  @Test
  fun `resume selects an open chat, fills an empty one or opens a new one without ending any chat`() = runTest {
    val chat = FakeExecutionChatRepository()
    chat.events = { flowOf(ChatEvent.Completed) }
    val chats = testNewTaskChats(chat)
    val a = chats.selected.value.ready()
    a.text = "build export"
    a.send()
    runCurrent()
    val b = chats.newChat()

    assertTrue(chats.resume(entry(checkNotNull(a.chat.value).sessionId)))
    assertSame(a, chats.selected.value)
    assertEquals(listOf(a, b), chats.open.value)

    chats.select(b)
    assertTrue(chats.resume(entry("saved-x")))
    assertEquals(listOf(a, b), chats.open.value)
    assertEquals("saved-x", b.chat.value?.sessionId)

    assertTrue(chats.resume(entry("saved-y")))
    val c = chats.open.value.last()
    assertEquals(3, chats.open.value.size)
    assertSame(c, chats.selected.value)
    assertEquals("saved-y", c.chat.value?.sessionId)

    assertFalse(chats.resume(entry("broken").copy(newTask = null)))
    assertEquals(listOf(a, b, c), chats.open.value)
    assertSame(c, chats.selected.value)
    val imageDraft = chats.newChat()
    val image = ChatImage(byteArrayOf(1), "image/png")
    imageDraft.addPastedImage(image)
    assertTrue(chats.resume(entry("saved-z")))
    assertNotSame(imageDraft, chats.selected.value)
    assertNull(imageDraft.chat.value)
    assertEquals(listOf(image), imageDraft.images)
    runCurrent()
    assertEquals(emptyList(), chat.ended)
  }

  @Test
  fun `restoring an unsupported draft retains its lines and blocks sends until an agent switch`() = runTest {
    val chat = FakeExecutionChatRepository()
    chat.events = { flowOf(ChatEvent.Completed) }
    val chats = testNewTaskChats(chat)
    val draft = chats.selected.value
    val saved = entry("unsupported-draft").copy(
      agent = ChatAgent("retired-agent", "kept-model", null),
      lines = listOf(ChatHistoryLine(ChatHistoryRole.Operator, "keep this"), ChatHistoryLine(ChatHistoryRole.Agent, "earlier reply")),
    )

    assertTrue(chats.resume(saved))
    runCurrent()

    val unsupported = checkNotNull(draft.chat.value)
    assertFalse(unsupported.thread.agentSupported.value)
    assertEquals(listOf("keep this", "earlier reply"), unsupported.thread.transcript.value.mapNotNull {
      (it as? ChatMessage.Operator)?.text ?: (it as? ChatMessage.Agent)?.text
    })
    assertEquals(1, unsupported.thread.transcript.value.filterIsInstance<ChatMessage.Notice>().count { it.text == dev.skillbill.desktop.feature.chat.UNAVAILABLE_AGENT_NOTICE })
    draft.text = "continue"
    assertFalse(draft.canSend())
    unsupported.sendNow()
    assertTrue(chat.requests.isEmpty())

    draft.agentId = "claude"
    draft.selectedAgent = ChatAgent("claude", null, null)
    draft.rebindChat()
    runCurrent()
    assertTrue(checkNotNull(draft.chat.value).thread.transcript.value.any { it is ChatMessage.Agent && it.text == "earlier reply" })
    draft.send()
    runCurrent()
    assertEquals("claude", chat.requests.last().agent.agentId)
    assertTrue(chat.requests.last().prompt.contains("keep this"))
  }

  // Leaving an idle chat without a summary request leaves its row on the fallback title; a missed update keeps it stale on screen.
  @Test
  fun `leaving an idle chat requests its summary and a written summary reloads Recent chats`() = runTest {
    val chat = FakeExecutionChatRepository()
    chat.events = { flowOf(ChatEvent.Completed) }
    val chats = testNewTaskChats(chat)
    val a = chats.selected.value.ready()
    a.text = "build export"
    a.send()
    runCurrent()

    chats.newChat()

    assertEquals(listOf(checkNotNull(a.chat.value).sessionId), chat.summaryRequests)

    chat.saved["saved-z"] = entry("saved-z")
    chat.summaryUpdateEvents.emit("saved-z")
    runCurrent()

    assertTrue(chats.saved.value.entries.any { it.sessionId == "saved-z" })
    a.close()
  }

  private fun NewTaskDraft.ready(): NewTaskDraft = apply {
    repository = NewTaskRepository(root, "repo")
    baseBranch = "main"
    agentId = "claude"
    selectedAgent = ChatAgent("claude", null, null)
  }

  private fun entry(sessionId: String) = ChatHistoryEntry(
    sessionId,
    null,
    ChatAgent("claude", null, null),
    Clock.System.now(),
    "Saved $sessionId",
    listOf(ChatHistoryLine(ChatHistoryRole.Operator, "Saved $sessionId")),
    ChatNewTaskContext(root, "main"),
  )
}
