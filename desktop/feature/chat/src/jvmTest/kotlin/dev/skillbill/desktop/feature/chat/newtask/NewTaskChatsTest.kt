package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.time.Clock

/** A window's open chats for tests that never send. */
internal fun unconfinedNewTaskChats() = NewTaskChats(
  FakeExecutionChatRepository(), FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
  WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
)

/** A window's open chats on the test scheduler. */
internal fun TestScope.testNewTaskChats(
  chat: ExecutionChatRepository = FakeExecutionChatRepository(),
  controls: WorkControlRepository = FakeWorkControlRepository(),
): NewTaskChats = NewTaskChats(chat, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System).apply {
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
}
