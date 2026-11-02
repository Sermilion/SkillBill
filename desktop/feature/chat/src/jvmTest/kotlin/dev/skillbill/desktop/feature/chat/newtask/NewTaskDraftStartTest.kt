package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskDraftStartTest {
  @Test
  fun `refusal keeps the conversation and success ends it and clears typed text`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Add export"
      send()
    }
    runCurrent()
    val sessionId = assertNotNull(draft.chat.value).sessionId
    controls.newTaskOutcome = NewTaskStartOutcome.Refused("Add more requirements.")

    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertNotNull(draft.chat.value)
    assertEquals("Add more requirements.", draft.offer.value.problem)

    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.text = "unsent text"
    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    assertNull(draft.chat.value)
    assertEquals("", draft.text)
    assertEquals(listOf(sessionId), chats.ended)
  }
  @Test
  fun `database change keeps the chat and retry uses the new session`() = runTest {
    val sessions = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("/old", 1L))
      override fun reopen() = Unit
    }
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = NewTaskDraft(chats, controls, sessions, ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Add export"
      send()
    }
    runCurrent()
    val chat = assertNotNull(draft.chat.value)
    sessions.state.value = DatabaseSessionState.Open("/new", 2L)
    controls.newTaskOutcome = NewTaskStartOutcome.Refused("The database changed. Try again.")

    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(1L, controls.newTaskRequests.single().expectedDatabaseSessionId)
    assertEquals(chat, draft.chat.value)
    assertEquals("The database changed. Try again.", draft.offer.value.problem)
    assertEquals(emptyList(), chats.ended)

    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(2L, controls.newTaskRequests.last().expectedDatabaseSessionId)
    assertNull(draft.chat.value)
  }

  @Test
  fun `goal and plan starts refuse an active chat turn`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flow { awaitCancellation() } } }
    val controls = FakeWorkControlRepository()
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Add export"
      send()
    }
    runCurrent()

    draft.start(NewTaskKind.GoalRun)
    draft.start(NewTaskKind.PlanOnly)

    assertTrue(controls.newTaskRequests.isEmpty())
    assertFalse(draft.offer.value.starting)
    draft.discard()
    runCurrent()
  }

  @Test
  fun `pending start refuses suggested turns`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val result = CompletableDeferred<NewTaskStartOutcome>()
    val workControls = object : WorkControlRepository by controls {
      override suspend fun startNewTask(request: NewTaskStart): NewTaskStartOutcome {
        controls.newTaskRequests += request
        return result.await()
      }
    }
    val draft = NewTaskDraft(chats, workControls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Add export"
      send()
    }
    runCurrent()
    val chat = assertNotNull(draft.chat.value)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    val action = ChatSuggestedAction("Continue", "Continue the work", ChatPermissionMode.Act)
    assertTrue(draft.offer.value.starting)
    assertFalse(draft.sendSuggestedAction(action))
    assertEquals(1, chats.requests.size)

    result.complete(NewTaskStartOutcome.Refused("Try again."))
    runCurrent()
    assertEquals(chat, draft.chat.value)
    assertFalse(draft.offer.value.starting)
    assertEquals("Try again.", draft.offer.value.problem)
    draft.discard()
    runCurrent()
  }

  @Test
  fun `successful start preserves text entered while its request is pending`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val result = CompletableDeferred<NewTaskStartOutcome>()
    val workControls = object : WorkControlRepository by controls {
      override suspend fun startNewTask(request: NewTaskStart): NewTaskStartOutcome {
        controls.newTaskRequests += request
        return result.await()
      }
    }
    val draft = NewTaskDraft(chats, workControls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Add export"
      send()
    }
    runCurrent()
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    draft.text = "Start another task"

    result.complete(NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun))
    runCurrent()

    assertNull(draft.chat.value)
    assertEquals("Start another task", draft.text)
  }

  @Test
  fun `start without open chat records monitor and verify kind and target`() = runTest {
    val chats = FakeExecutionChatRepository()
    val controls = FakeWorkControlRepository()
    controls.newTaskOutcome = NewTaskStartOutcome.Started("monitor", NewTaskKind.Monitor)
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
    }
    draft.start(NewTaskKind.Monitor, target = "https://github.com/org/repo/pull/1")
    runCurrent()
    with(controls.newTaskRequests.single()) {
      assertEquals(NewTaskKind.Monitor, kind)
      assertEquals("https://github.com/org/repo/pull/1", target)
    }

    controls.newTaskOutcome = NewTaskStartOutcome.Started("verify", NewTaskKind.Verify)
    draft.start(NewTaskKind.Verify, target = "check the review on this branch")
    runCurrent()
    with(controls.newTaskRequests.last()) {
      assertEquals(NewTaskKind.Verify, kind)
      assertEquals("check the review on this branch", target)
    }
  }

}
