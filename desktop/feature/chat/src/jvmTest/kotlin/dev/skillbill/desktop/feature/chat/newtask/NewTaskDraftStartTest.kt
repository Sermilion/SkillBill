package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
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
import kotlinx.coroutines.test.TestScope
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
  fun `refusal and success both keep the conversation`() = runTest {
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
    val chat = assertNotNull(draft.chat.value)
    controls.newTaskOutcome = NewTaskStartOutcome.Refused("Add more requirements.")

    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertNotNull(draft.chat.value)
    assertEquals("Add more requirements.", draft.offer.value.problem)

    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.text = "unsent text"
    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    assertEquals(chat, draft.chat.value)
    assertNull(draft.offer.value.problem)
    assertEquals("unsent text", draft.text)
    assertEquals(emptyList(), chats.ended)
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
    assertEquals(chat, draft.chat.value)
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
    val chat = assertNotNull(draft.chat.value)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    draft.text = "Start another task"

    result.complete(NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun))
    runCurrent()

    assertEquals(chat, draft.chat.value)
    assertEquals("Start another task", draft.text)
  }

  @Test
  fun `started goal run and plan keep the chat and its saved draft`() = runTest {
    val confirmations = mapOf(NewTaskKind.GoalRun to "Started goal run APP-1", NewTaskKind.PlanOnly to "Started plan APP-1")
    confirmations.forEach { (kind, confirmation) ->
      val chats = FakeExecutionChatRepository()
      val controls = FakeWorkControlRepository().apply { newTaskOutcome = NewTaskStartOutcome.Started("APP-1", kind) }
      val draft = chattingDraft(chats, controls)
      val chat = assertNotNull(draft.chat.value)

      draft.start(kind)
      runCurrent()

      assertEquals(chat, draft.chat.value)
      assertTrue(chat.sessionId in chats.saved)
      assertEquals(emptyList(), chats.ended)
      assertEquals(confirmation, draft.offer.value.started)
    }
  }

  @Test
  fun `start through the issue prefix step clears the prefix panel`() = runTest {
    val controls = FakeWorkControlRepository().apply { newTaskOutcome = NewTaskStartOutcome.NeedsPrefix(listOf("APP")) }
    val draft = chattingDraft(FakeExecutionChatRepository(), controls)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(NewTaskKind.GoalRun, draft.offer.value.prefixKind)

    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun, "APP")
    runCurrent()

    assertNull(draft.offer.value.prefixKind)
    assertEquals(emptyList(), draft.offer.value.choices)
  }

  @Test
  fun `started chat refuses repeat runs until a new turn but allows standalone work`() = runTest {
    val goalStarted = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    val controls = FakeWorkControlRepository().apply { newTaskOutcome = goalStarted }
    val draft = chattingDraft(FakeExecutionChatRepository(), controls)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    draft.start(NewTaskKind.GoalRun)
    draft.start(NewTaskKind.PlanOnly)
    runCurrent()
    assertEquals(1, controls.newTaskRequests.size)
    assertFalse(draft.offer.value.starting)

    controls.newTaskOutcome = NewTaskStartOutcome.Started("review", NewTaskKind.Review)
    draft.start(NewTaskKind.Review)
    runCurrent()
    assertEquals(2, controls.newTaskRequests.size)
    assertEquals("Started goal run APP-1", draft.offer.value.started)

    draft.text = "Also add import"
    draft.send()
    runCurrent()
    assertNull(draft.offer.value.started)
    controls.newTaskOutcome = goalStarted
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(3, controls.newTaskRequests.size)
  }

  @Test
  fun `discard after a start deletes the draft and the next chat can start`() = runTest {
    val chats = FakeExecutionChatRepository()
    val controls = FakeWorkControlRepository().apply { newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun) }
    val draft = chattingDraft(chats, controls)
    val sessionId = assertNotNull(draft.chat.value).sessionId
    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    draft.discard()
    runCurrent()

    assertEquals(listOf(sessionId), chats.ended)
    assertFalse(sessionId in chats.saved)
    assertNull(draft.offer.value.started)

    draft.text = "Add import"
    draft.send()
    runCurrent()
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(2, controls.newTaskRequests.size)
  }

  @Test
  fun `start that returns after its chat left does not guard the next chat`() = runTest {
    val controls = FakeWorkControlRepository()
    val result = CompletableDeferred<NewTaskStartOutcome>()
    val workControls = object : WorkControlRepository by controls {
      override suspend fun startNewTask(request: NewTaskStart): NewTaskStartOutcome {
        controls.newTaskRequests += request
        return result.await()
      }
    }
    val draft = chattingDraft(FakeExecutionChatRepository(), workControls)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    draft.discard()
    result.complete(NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun))
    runCurrent()

    assertNull(draft.offer.value.started)
    assertFalse(draft.offer.value.starting)

    draft.text = "Add import"
    draft.send()
    runCurrent()
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(2, controls.newTaskRequests.size)
  }

  @Test
  fun `offer card start carries the title from the agent's start action`() = runTest {
    val action = ChatSuggestedAction("Start", "Go", ChatPermissionMode.Act, NewTaskActionKind.Goal, "Add dark mode setting")
    val chats = FakeExecutionChatRepository().apply {
      events = { kotlinx.coroutines.flow.flowOf(ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed) }
    }
    val controls = FakeWorkControlRepository()
    val draft = chattingDraft(chats, controls)

    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    assertEquals("Add dark mode setting", controls.newTaskRequests.single().title)
  }

  private fun TestScope.chattingDraft(chats: FakeExecutionChatRepository, controls: WorkControlRepository): NewTaskDraft {
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Add export"
      send()
    }
    runCurrent()
    return draft
  }
}
