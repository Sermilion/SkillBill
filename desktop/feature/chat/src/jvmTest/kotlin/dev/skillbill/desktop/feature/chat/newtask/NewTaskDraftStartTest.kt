package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import dev.skillbill.desktop.core.domain.model.ChatAgent
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
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
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
  fun `reported cross project mention asks for a key and selection alone does not launch`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository().apply { newTaskOutcome = NewTaskStartOutcome.NeedsKey(listOf("SKILL-530")) }
    val draft = draftWithFinishedTurn(chats, controls, "0AC-103 has multiple block reasons where Audit found ACs not implemented")
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertNull(controls.newTaskRequests.single().keyChoice)
    assertEquals(listOf("SKILL-530"), draft.offer.value.choices)
    draft.selectIssueKey("SKILL")
    assertNull(draft.offer.value.selectedKey)
    draft.selectIssueKey("SKILL-530")
    runCurrent()
    assertEquals(1, controls.newTaskRequests.size)
    assertEquals("SKILL-530", draft.offer.value.selectedKey)
    controls.newTaskOutcome = NewTaskStartOutcome.Started("SKILL-530", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun, draft.offer.value.selectedKey)
    runCurrent()
    assertEquals("SKILL-530", controls.newTaskRequests.last().keyChoice)
  }

  @Test
  fun `changing repositories refuses a launch using the previous conversation identity`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls, "SKILL-530")
    draft.repository = NewTaskRepository("/another/repository", "another")
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertTrue(controls.newTaskRequests.isEmpty())
    assertNull(draft.offer.value.selectedKey)
    draft.repository = NewTaskRepository("/work/repo", "repo")
    assertNull(draft.acceptedIssueKey)
    assertTrue(draft.issueKeyProposals.isEmpty())
  }

  @Test
  fun `empty goal identity asks for a key without minting`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository().apply { newTaskOutcome = NewTaskStartOutcome.NeedsKey(listOf("APP")) }
    val draft = draftWithFinishedTurn(chats, controls)

    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    with(controls.newTaskRequests.single()) {
      assertNull(keyChoice)
      assertFalse(reuseRequested)
    }
    assertEquals(listOf("APP"), draft.offer.value.choices)
  }

  @Test
  fun `typed offer key is passed and reuse wording enables terminal reuse`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls, "Please reuse SKILL-470")

    draft.start(NewTaskKind.GoalRun, "SKILL-470")
    runCurrent()

    with(controls.newTaskRequests.single()) {
      assertEquals("SKILL-470", keyChoice)
      assertTrue(reuseRequested)
    }
  }

  @Test
  fun `operator URL and sentence mentions do not become accepted identity`() = runTest {
    val url = "https://linear.app/team/issue/SKILL-502/title"
    val urlChats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val urlControls = FakeWorkControlRepository()
    val urlDraft = draftWithFinishedTurn(urlChats, urlControls, url)
    urlDraft.start(NewTaskKind.GoalRun)
    runCurrent()

    with(urlControls.newTaskRequests.single()) {
      assertNull(keyChoice)
      assertFalse(reuseRequested)
    }
    assertEquals(listOf("SKILL-502"), urlDraft.issueKeyProposals)
    assertNull(urlDraft.acceptedIssueKey)

    val sentenceChats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val sentenceControls = FakeWorkControlRepository()
    val sentenceDraft = draftWithFinishedTurn(sentenceChats, sentenceControls, "Please fix SKILL-476 while adding export")
    sentenceDraft.start(NewTaskKind.GoalRun)
    runCurrent()

    assertNull(sentenceControls.newTaskRequests.single().keyChoice)
  }

  @Test
  fun `refusal and success both keep the conversation`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
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
    assertEquals(listOf("Add more requirements."), errors(chat))

    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.text = "unsent text"
    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    assertEquals(chat, draft.chat.value)
    assertEquals("unsent text", draft.text)
    assertEquals(emptyList(), chats.ended)
    assertEquals(listOf("Started goal run APP-1. Send another message to start again."), confirmations(chat))
  }
  @Test
  fun `database change keeps the chat and retry uses the new session`() = runTest {
    val sessions = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("/old", 1L))
      override fun reopen() = Unit
    }
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = NewTaskDraft(chats, controls, sessions, ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
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
    assertEquals(listOf("The database changed. Try again."), errors(chat))
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
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
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
    val draft = NewTaskDraft(chats, workControls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
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
    assertEquals(listOf("Try again."), errors(chat))
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
    val draft = NewTaskDraft(chats, workControls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
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

    assertNotNull(draft.chat.value)
    assertEquals("Start another task", draft.text)
  }

  @Test
  fun `start without open chat records monitor and verify kind, target and action branch`() = runTest {
    val chats = FakeExecutionChatRepository()
    val controls = FakeWorkControlRepository()
    controls.newTaskOutcome = NewTaskStartOutcome.Started("monitor", NewTaskKind.Monitor)
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
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
    draft.baseBranch = "origin/rel"
    val verify = ChatSuggestedAction(
      "Run operation:verify",
      "Verify the branch.",
      ChatPermissionMode.Act,
      NewTaskActionKind.Verify,
      branch = " feat/fp-5175 ",
    )
    draft.start(NewTaskKind.Verify, target = "check the review on this branch", action = verify)
    runCurrent()
    with(controls.newTaskRequests.last()) {
      assertEquals(NewTaskKind.Verify, kind)
      assertEquals("check the review on this branch", target)
      assertEquals("origin/rel", baseBranch)
      assertEquals("feat/fp-5175", branch)
    }
    assertEquals(null, controls.newTaskRequests.first().baseBranch)
    assertEquals(null, controls.newTaskRequests.first().branch)
  }

  @Test
  fun `starting a goal after a titled reply passes the title`() = runTest {
    val chats = FakeExecutionChatRepository().apply {
      events = {
        kotlinx.coroutines.flow.flowOf(
          ChatEvent.SuggestedActions(
            listOf(
              ChatSuggestedAction(
                "Start a goal run",
                "Implement the requested change in this repository.",
                ChatPermissionMode.Act,
                NewTaskActionKind.Goal,
                title = "Add dark mode setting",
              ),
            ),
          ),
          ChatEvent.Completed,
        )
      }
    }
    val controls = FakeWorkControlRepository()
    val draft = NewTaskDraft(
      chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics(),
    ).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Add export"
      send()
    }
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals("Add dark mode setting", controls.newTaskRequests.single().title)
  }

  @Test
  fun `a clicked goal action leads the requirements and survives a key choice`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls)
    val section = ChatSuggestedAction("Section 1", "Implement section 1 only.", ChatPermissionMode.Act, NewTaskActionKind.Goal, title = "Mobile operations")
    controls.newTaskOutcome = NewTaskStartOutcome.NeedsKey(listOf("APP-2"))
    draft.start(NewTaskKind.GoalRun, action = section)
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-2", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun, "APP-2")
    runCurrent()

    controls.newTaskRequests.forEach { request ->
      assertEquals("Implement section 1 only.", request.requirements)
      assertEquals("Mobile operations", request.title)
    }
    assertEquals("APP-2", controls.newTaskRequests.last().keyChoice)
  }

  @Test
  fun `a null action title survives plan prefix retry and the next start uses the transcript`() = runTest {
    val unrelated = ChatSuggestedAction("Other", "Implement another change.", ChatPermissionMode.Act, NewTaskActionKind.Goal, title = "Other change")
    val chats = FakeExecutionChatRepository().apply {
      events = { kotlinx.coroutines.flow.flowOf(ChatEvent.SuggestedActions(listOf(unrelated)), ChatEvent.Completed) }
    }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls)
    assertEquals("Other change", newTaskTitle(assertNotNull(draft.chat.value).thread.transcript.value))
    val action = ChatSuggestedAction("Plan", "Plan the requested export.", ChatPermissionMode.Act, NewTaskActionKind.Plan)
    controls.newTaskOutcome = NewTaskStartOutcome.NeedsKey(listOf("APP"))

    draft.start(NewTaskKind.PlanOnly, action = action)
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.PlanOnly)
    draft.start(NewTaskKind.PlanOnly, "APP-2")
    runCurrent()

    assertEquals("Plan the requested export.", controls.newTaskRequests[0].requirements)
    assertNull(controls.newTaskRequests[0].title)
    assertEquals("Plan the requested export.", controls.newTaskRequests[1].requirements)
    assertNull(controls.newTaskRequests[1].title)

    draft.text = "Start another task"
    draft.send()
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.NeedsKey(listOf("APP"))
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-2", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun, "APP-3")
    runCurrent()

    val transcript = assertNotNull(draft.chat.value).thread.transcript.value
    assertEquals(newTaskRequirements(transcript), controls.newTaskRequests[2].requirements)
    assertEquals(newTaskTitle(transcript), controls.newTaskRequests[2].title)
    assertEquals(newTaskRequirements(transcript), controls.newTaskRequests[3].requirements)
    assertEquals(newTaskTitle(transcript), controls.newTaskRequests[3].title)
  }

  @Test
  fun `goal and plan failures clear the started claim and retain the accepted identity for retry`() = runTest {
    for (kind in listOf(NewTaskKind.GoalRun, NewTaskKind.PlanOnly)) {
      val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
      val controls = FakeWorkControlRepository()
      val failure = CompletableDeferred<String?>()
      val draft = draftWithFinishedTurn(chats, controls, "SKILL-514")
      controls.newTaskOutcome = NewTaskStartOutcome.Started("SKILL-514", kind, awaitFailure = { failure.await() })

      draft.start(kind)
      runCurrent()
      assertNotNull(draft.offer.value.started)
      failure.complete("Database schema is incompatible. Full log: new-task.log")
      runCurrent()

      assertNull(draft.offer.value.started)
      val chat = assertNotNull(draft.chat.value)
      assertTrue(chat.thread.transcript.value.any { it is ChatMessage.Error && it.text.contains("Database schema is incompatible.") })
      controls.newTaskOutcome = NewTaskStartOutcome.Started("SKILL-514", kind)
      draft.start(kind)
      runCurrent()
      assertEquals(listOf("SKILL-514", "SKILL-514"), controls.newTaskRequests.map { it.keyChoice })
    }
  }

  @Test
  fun `standalone start reports in the chat and a later failure explains itself`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val failure = CompletableDeferred<String?>()
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "watch the pr"
      send()
    }
    runCurrent()
    val chat = assertNotNull(draft.chat.value)
    controls.newTaskOutcome = NewTaskStartOutcome.Started("monitor", NewTaskKind.Monitor, awaitFailure = { failure.await() })

    draft.start(NewTaskKind.Monitor, target = "https://github.com/acme/app/pull/42")
    runCurrent()

    assertEquals(chat, draft.chat.value)
    assertTrue(notices(chat).any { it.startsWith("Started monitor") }, notices(chat).toString())

    failure.complete("Monitor target refused.")
    runCurrent()
    assertTrue("monitor stopped. Monitor target refused." in notices(chat), notices(chat).toString())
  }

  @Test
  fun `monitor start trims the target before launch`() = runTest {
    val chats = FakeExecutionChatRepository()
    val controls = FakeWorkControlRepository()
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
    }
    controls.newTaskOutcome = NewTaskStartOutcome.Started("monitor", NewTaskKind.Monitor)
    draft.start(NewTaskKind.Monitor, target = " branch:main ")
    runCurrent()
    assertEquals("branch:main", controls.newTaskRequests.single().target)
  }

  @Test
  fun `standalone refusal and goal refusal both go to the chat`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "review this"
      send()
    }
    runCurrent()
    val chat = assertNotNull(draft.chat.value)
    controls.newTaskOutcome = NewTaskStartOutcome.Refused("No.")

    draft.start(NewTaskKind.Review)
    runCurrent()
    assertEquals(listOf("No."), notices(chat))
    assertEquals(emptyList(), errors(chat))

    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(listOf("No."), notices(chat))
    assertEquals(listOf("No."), errors(chat))
  }

  private fun notices(chat: NewTaskChat): List<String> =
    chat.thread.transcript.value.filterIsInstance<ChatMessage.Notice>().map { it.text }

  private fun errors(chat: NewTaskChat): List<String> =
    chat.thread.transcript.value.filterIsInstance<ChatMessage.Error>().map { it.text }

  private fun confirmations(chat: NewTaskChat): List<String> =
    chat.thread.transcript.value.filterIsInstance<ChatMessage.Notice>().filter { it.confirmation }.map { it.text }

  @Test
  fun `starting a goal after chip rebind posts the new repository and agent`() = runTest {
    val chats = FakeExecutionChatRepository(supported = setOf("claude", "codex")).apply {
      events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) }
    }
    val controls = FakeWorkControlRepository()
    val firstRoot = createTempDirectory("first-repo").also { it.toFile().deleteOnExit() }.toString()
    val secondRoot = createTempDirectory("second-repo").also { it.toFile().deleteOnExit() }.toString()
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository(firstRoot, "first")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Add export"
      send()
    }
    runCurrent()
    draft.repository = NewTaskRepository(secondRoot, "second")
    draft.agentId = "codex"
    draft.selectedAgent = ChatAgent("codex", null, null)
    draft.rebindChat()
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.Refused("Add more requirements.")

    draft.start(NewTaskKind.GoalRun)
    runCurrent()

    val request = controls.newTaskRequests.single()
    assertEquals(secondRoot, request.repoRoot)
    assertEquals("codex", request.agentId)
  }

  @Test
  fun `started goal run keeps saved draft`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls)
    val sessionId = assertNotNull(draft.chat.value).sessionId
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertTrue(chats.saved.contains(sessionId))
    assertEquals(emptyList(), chats.ended)
    assertEquals("Started goal run APP-1", draft.offer.value.started)
    assertEquals(listOf("Started goal run APP-1. Send another message to start again."), confirmations(assertNotNull(draft.chat.value)))
  }

  @Test
  fun `started plan only keeps saved draft`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls)
    val sessionId = assertNotNull(draft.chat.value).sessionId
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.PlanOnly)
    draft.start(NewTaskKind.PlanOnly)
    runCurrent()
    assertTrue(chats.saved.contains(sessionId))
    assertEquals(emptyList(), chats.ended)
    assertEquals("Started plan APP-1", draft.offer.value.started)
    assertEquals(listOf("Started plan APP-1. Send another message to start again."), confirmations(assertNotNull(draft.chat.value)))
  }

  @Test
  fun `needs key keeps its choices until a complete key starts successfully`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls)
    controls.newTaskOutcome = NewTaskStartOutcome.NeedsKey(listOf("APP"))
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(NewTaskKind.GoalRun, draft.offer.value.prefixKind)
    assertEquals(listOf("APP"), draft.offer.value.choices)
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-2", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun, "APP-2")
    runCurrent()
    assertNull(draft.offer.value.prefixKind)
    assertEquals(emptyList(), draft.offer.value.choices)
  }

  @Test
  fun `started guard blocks repeat run starts until a turn is admitted`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls)
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(1, controls.newTaskRequests.size)
    draft.start(NewTaskKind.GoalRun)
    draft.start(NewTaskKind.PlanOnly)
    runCurrent()
    assertEquals(1, controls.newTaskRequests.size)
    controls.newTaskOutcome = NewTaskStartOutcome.Started("review", NewTaskKind.Review)
    draft.start(NewTaskKind.Review)
    runCurrent()
    assertEquals(2, controls.newTaskRequests.size)
    assertEquals("Started goal run APP-1", draft.offer.value.started)
    draft.text = "Continue planning"
    draft.send()
    runCurrent()
    assertNull(draft.offer.value.started)
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-2", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(3, controls.newTaskRequests.size)
  }

  @Test
  fun `discard after start ends session and clears guard`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val draft = draftWithFinishedTurn(chats, controls)
    val sessionId = assertNotNull(draft.chat.value).sessionId
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    draft.discard()
    runCurrent()
    assertEquals(listOf(sessionId), chats.ended)
    assertFalse(chats.saved.contains(sessionId))
    assertNull(draft.offer.value.started)
    draft.text = "New work"
    draft.send()
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-2", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(2, controls.newTaskRequests.size)
  }

  @Test
  fun `pending start on discarded chat leaves guard null on next chat`() = runTest {
    val chats = FakeExecutionChatRepository().apply { events = { kotlinx.coroutines.flow.flowOf(ChatEvent.Completed) } }
    val controls = FakeWorkControlRepository()
    val result = CompletableDeferred<NewTaskStartOutcome>()
    val workControls = object : WorkControlRepository by controls {
      override suspend fun startNewTask(request: NewTaskStart): NewTaskStartOutcome {
        controls.newTaskRequests += request
        return result.await()
      }
    }
    val draft = draftWithFinishedTurn(chats, workControls)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    draft.discard()
    runCurrent()
    result.complete(NewTaskStartOutcome.Started("APP-1", NewTaskKind.GoalRun))
    runCurrent()
    assertNull(draft.offer.value.started)
    assertFalse(draft.offer.value.starting)
    draft.text = "Fresh chat"
    draft.send()
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.Started("APP-2", NewTaskKind.GoalRun)
    draft.start(NewTaskKind.GoalRun)
    runCurrent()
    assertEquals(2, controls.newTaskRequests.size)
  }

  private fun TestScope.draftWithFinishedTurn(
    chats: FakeExecutionChatRepository,
    controls: WorkControlRepository,
    operatorText: String = "Add export",
  ): NewTaskDraft {
    return NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics()).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/repo", "repo")
      agentId = "claude"
      selectedAgent = chats.agent
      text = operatorText
      send()
    }.also { runCurrent() }
  }
}
