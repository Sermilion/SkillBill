package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import androidx.compose.runtime.snapshots.Snapshot
import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.OperationCardDetailsView
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.OperationCardView
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.VerifyQaChoice
import dev.skillbill.desktop.core.domain.model.VerifyReviewModeChoice
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.VerifyStartChoices
import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
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
import dev.skillbill.desktop.core.domain.model.SessionMoveResult
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.GoalWorktree
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.domain.model.MainCheckoutOutcome
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.core.ui.usage.model.SelectedNewTaskUsageContext
import dev.skillbill.desktop.feature.chat.newtask.testDatabaseSessionController
import dev.skillbill.desktop.feature.chat.newtask.unconfinedNewTaskChats
import dev.skillbill.desktop.feature.chat.newtask.NewTaskRepository
import dev.skillbill.desktop.feature.chat.newtask.NewTaskChats
import dev.skillbill.desktop.feature.chat.newtask.testNewTaskChats
import kotlin.test.assertNotNull
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
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

  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
    tempDirs.forEach { it.toFile().deleteRecursively() }
  }

  /** A real directory standing in for a goal worktree; `toRealPath` avoids the macOS `/var` symlink. */
  private fun worktreeDir(): String = Files.createTempDirectory("chat-worktree").toRealPath().also(tempDirs::add).toString()

  @Test
  fun `resuming an unsupported saved chat keeps history and gives recovery guidance`() = runTest {
    val item = workItem("SKILL-19")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    chat.events = { flowOf(ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val saved = ChatHistoryEntry(
      sessionId = "retired-session",
      executionId = item.id,
      agent = ChatAgent("retired-agent", "kept-model", "high"),
      updatedAt = clock.now(),
      title = "Saved work",
      lines = listOf(ChatHistoryLine(ChatHistoryRole.Operator, "keep this"), ChatHistoryLine(ChatHistoryRole.Agent, "earlier reply")),
    )

    workspace.resume(item.id, saved)
    runCurrent()

    assertFalse(session.agentSupported.value)
    assertEquals(listOf("keep this", "earlier reply"), session.transcript.value.mapNotNull {
      (it as? ChatMessage.Operator)?.text ?: (it as? ChatMessage.Agent)?.text
    })
    assertEquals(1, session.transcript.value.filterIsInstance<ChatMessage.Notice>().count { it.text == UNAVAILABLE_AGENT_NOTICE })

    workspace.resume(item.id, saved.copy(lines = saved.lines + ChatHistoryLine(ChatHistoryRole.Notice, UNAVAILABLE_AGENT_NOTICE)))
    runCurrent()
    assertEquals(1, session.transcript.value.filterIsInstance<ChatMessage.Notice>().count { it.text == UNAVAILABLE_AGENT_NOTICE })

    workspace.switchAgent(item.id, "codex")
    runCurrent()
    assertTrue(session.agentSupported.value)
    workspace.send(item.id, "continue")
    runCurrent()
    assertEquals("codex", chat.requests.last().agent.agentId)
    assertEquals(1, session.transcript.value.filterIsInstance<ChatMessage.Notice>().count { it.text == UNAVAILABLE_AGENT_NOTICE })
  }

  @Test
  fun `an immediately replayed reply has its observer identity before collection starts`() = runTest {
    val item = workItem("SKILL-530")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val repository = object : ExecutionChatRepository by chat {
      override fun unprompted(sessionId: String) = flowOf(ChatEvent.TextDelta("Already replying"), ChatEvent.Completed)
    }
    val workspace = ChatWorkspace(
      repository, details, controls, testDatabaseSessionController(),
      WindowCoroutineScope(CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Unconfined)),
      clock, panes, selection, unconfinedNewTaskChats(), diagnostics = RecordingDiagnostics(),
    )
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    assertTrue(session.transcript.value.filterIsInstance<ChatMessage.Agent>().any { it.text == "Already replying" })
    assertTrue(workspace.closeAll())
    assertTrue(session.job.isCancelled)
  }

  @Test
  fun `selected New task usage keeps the executing agent and follows rotated session`() = runTest {
    chat.events = { flow { awaitCancellation() } }
    val chats = NewTaskChats(chat, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), clock, diagnostics = RecordingDiagnostics())
    chats.chatDispatcher = StandardTestDispatcher(testScheduler)
    val original = ChatAgent("claude", "model-a", "high", "/profiles/claude-a")
    val draft = chats.selected.value.apply {
      repository = NewTaskRepository("/repo", "repo")
      agentId = "claude"
      selectedAgent = original
      text = "start work"
    }
    val workspace = workspace(chats)
    draft.send()
    runCurrent()
    val oldSession = checkNotNull(draft.chat.value).sessionId
    val running = assertIs<SelectedNewTaskUsageContext.Ready>(workspace.selectedNewTaskUsageContext.value)
    assertEquals(original, running.agent)
    assertEquals(oldSession, running.logicalSessionId)
    assertTrue(running.activeWork)

    draft.selectedAgent = ChatAgent("claude", "model-b", "low", "/profiles/claude-b")
    draft.rebindChat()
    runCurrent()
    val retained = assertIs<SelectedNewTaskUsageContext.Ready>(workspace.selectedNewTaskUsageContext.value)
    assertEquals(original, retained.agent)
    assertEquals(chat.requests.single().agent, retained.agent)

    draft.stop()
    runCurrent()
    val rotated = assertIs<SelectedNewTaskUsageContext.Ready>(workspace.selectedNewTaskUsageContext.value)
    assertNotEquals(oldSession, rotated.logicalSessionId)
    assertEquals(rotated.logicalSessionId, draft.chat.value?.sessionIdFlow?.value)
    assertEquals(original, rotated.agent)
    workspace.closeAll()
  }

  private fun TestScope.workspace(
    chats: NewTaskChats = unconfinedNewTaskChats(),
    repository: ExecutionChatRepository = chat,
  ): ChatWorkspace {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    return ChatWorkspace(repository, details, controls, testDatabaseSessionController(), WindowCoroutineScope(backgroundScope), clock, panes, selection, chats, diagnostics = RecordingDiagnostics())
  }

  // A window that closes while one New task chat could not be saved, or a retry that leaves the rest open.
  @Test
  fun `closing the window keeps it open while one New task chat fails to save, and the retry closes it`() = runTest {
    var failing: String? = null
    val flaky = object : ExecutionChatRepository by chat {
      override suspend fun saveNewTaskDraft(entry: ChatHistoryEntry): Boolean {
        if (entry.sessionId == failing) {
          failing = null
          return false
        }
        return chat.saveNewTaskDraft(entry)
      }
    }
    val chats = testNewTaskChats(flaky)
    chats.selected.value.apply {
      repository = NewTaskRepository("/existing/repo", "repo")
      baseBranch = "main"
      agentId = "claude"
      selectedAgent = ChatAgent("claude", null, null)
    }
    val drafts = List(3) { index ->
      val draft = if (index == 0) chats.selected.value else chats.newChat()
      draft.text = "task $index"
      draft.send()
      runCurrent()
      draft
    }
    val workspace = workspace(chats)
    failing = checkNotNull(drafts[1].chat.value).sessionId

    assertFalse(workspace.closeAll())
    runCurrent()
    assertNotNull(drafts[1].chat.value)

    assertTrue(workspace.closeAll())
    runCurrent()
    assertTrue(drafts.all { it.chat.value == null })
  }

  @Test
  fun `goal intake prefill keeps a nonblank draft and fills an empty draft`() = runTest {
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value
    val workspace = workspace(chats)
    assertTrue(workspace.prefillGoalIntake("/verified/repo", "feature/verify", "Fix criterion 2"))
    assertEquals("Fix criterion 2", draft.text)
    assertEquals(NewTaskRepository("/verified/repo", "repo"), draft.repository)
    assertEquals("feature/verify", draft.baseBranch)

    draft.text = "operator draft"
    assertFalse(workspace.prefillGoalIntake("/other", "other", "replacement"))
    assertEquals("operator draft", draft.text)
    assertEquals(NewTaskRepository("/verified/repo", "repo"), draft.repository)
    assertEquals("feature/verify", draft.baseBranch)
    workspace.closeAll()
  }

  @Test
  fun `goal intake rejects an empty composer when a conversation exists for the same or another repository`() = runTest {
    val chats = NewTaskChats(
      chat, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), clock, diagnostics = RecordingDiagnostics(),
    )
    val draft = chats.selected.value.apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/existing/repo", "repo")
      baseBranch = "existing-branch"
      agentId = "claude"
      selectedAgent = ChatAgent("claude", null, null)
    }
    val workspace = workspace(chats)
    draft.text = "unrelated existing work"
    draft.send()
    runCurrent()
    draft.text = ""
    val conversation = draft.chat.value!!
    val transcript = conversation.thread.transcript.value
    val sessionId = conversation.sessionId

    assertFalse(workspace.prefillGoalIntake("/existing/repo", "verify-branch", "Verify fixes"))
    assertEquals("", draft.text)
    assertEquals(NewTaskRepository("/existing/repo", "repo"), draft.repository)
    assertEquals("existing-branch", draft.baseBranch)
    assertEquals(sessionId, draft.chat.value?.sessionId)
    assertEquals(transcript, conversation.thread.transcript.value)

    assertFalse(workspace.prefillGoalIntake("/other/repo", "other-branch", "Verify fixes"))
    assertEquals("", draft.text)
    assertEquals(NewTaskRepository("/existing/repo", "repo"), draft.repository)
    assertEquals("existing-branch", draft.baseBranch)
    assertEquals(sessionId, draft.chat.value?.sessionId)
    assertEquals(transcript, conversation.thread.transcript.value)
    workspace.closeAll()
  }

  @Test
  fun `goal intake rejects an empty composer while its conversation turn is active`() = runTest {
    chat.events = { flow { awaitCancellation() } }
    val chats = NewTaskChats(
      chat, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), clock, diagnostics = RecordingDiagnostics(),
    )
    val draft = chats.selected.value.apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/existing/repo", "repo")
      baseBranch = "existing-branch"
      agentId = "claude"
      selectedAgent = ChatAgent("claude", null, null)
    }
    val workspace = workspace(chats)
    draft.text = "unrelated active work"
    draft.send()
    runCurrent()
    draft.text = ""
    val conversation = draft.chat.value!!
    val transcript = conversation.thread.transcript.value
    assertTrue(conversation.thread.busy.value)

    assertFalse(workspace.prefillGoalIntake("/other/repo", "verify-branch", "Verify fixes"))
    assertEquals("", draft.text)
    assertEquals(NewTaskRepository("/existing/repo", "repo"), draft.repository)
    assertEquals("existing-branch", draft.baseBranch)
    assertEquals(transcript, conversation.thread.transcript.value)
    assertTrue(conversation.thread.busy.value)
    workspace.closeAll()
  }

  @Test
  fun `goal intake rejects an empty composer when a conversation has been resumed`() = runTest {
    val chats = NewTaskChats(
      chat, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), clock, diagnostics = RecordingDiagnostics(),
    )
    val draft = chats.selected.value.apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
    }
    val workspace = workspace(chats)
    val saved = ChatHistoryEntry(
      sessionId = "resumed-session",
      executionId = null,
      agent = ChatAgent("claude", null, null),
      updatedAt = clock.now(),
      title = "Existing work",
      lines = listOf(ChatHistoryLine(ChatHistoryRole.Operator, "unrelated existing work")),
      newTask = ChatNewTaskContext(".", "existing-branch"),
    )
    assertTrue(draft.resume(saved))
    val conversation = draft.chat.value!!
    val transcript = conversation.thread.transcript.value

    assertFalse(workspace.prefillGoalIntake("/other/repo", "verify-branch", "Verify fixes"))
    assertEquals("", draft.text)
    assertEquals(NewTaskRepository(".", "."), draft.repository)
    assertEquals("existing-branch", draft.baseBranch)
    assertEquals("resumed-session", draft.chat.value?.sessionId)
    assertEquals(transcript, conversation.thread.transcript.value)
    workspace.closeAll()
  }

  @Test
  fun `checkout change retains an acquired follow up but drains a revoked pending write only after moving context`() = runTest {
    for (uncertain in listOf(true, false)) {
      val repository = FakeExecutionChatRepository()
      repository.steeringPolicies["claude"] = ChatSteeringPolicy.LiveWhenAvailable
      val result = CompletableDeferred<ChatSteeringResult>()
      repository.steeringResult = { result.await() }
      repository.settledSteering = { _, id ->
        if (uncertain) ChatSteeringResult.DeliveryUncertain(id, "write acquired")
        else ChatSteeringResult.NoActiveTurn(id, "revoked before write")
      }
      val worktree = worktreeDir()
      val active = workItem("SKILL-530-$uncertain").copy(repositoryRoot = "/repo",
        goalWorktree = GoalWorktree(worktree, GoalWorktreeState.Active, null, null))
      val detail = MutableStateFlow(loaded(active))
      details.details = mapOf(active.id to detail)
      val workspace = workspace(repository = repository)
      workspace.open(active)
      runCurrent()
      val session = workspace.session(active.id)!!
      repository.unpromptedEvents.emit(session.sessionId to ChatEvent.TextDelta("Background"))
      runCurrent()
      val image = ChatImage(byteArrayOf(1), "image/png")
      workspace.send(active.id, "retained", images = listOf(image))
      workspace.sendNow(active.id)
      runCurrent()
      repository.unpromptedEvents.emit(session.sessionId to ChatEvent.Completed)
      runCurrent()
      assertEquals(1, repository.requests.size)
      detail.value = loaded(active.copy(goalWorktree = active.goalWorktree?.copy(state = GoalWorktreeState.Removed)))
      runCurrent()
      if (uncertain) {
        assertTrue(repository.moveCalls.isEmpty())
        assertEquals(1, repository.requests.size)
        assertTrue(session.thread.delivery.value!!.images.single() === image)
        workspace.retryDelivery(active.id, session.thread.delivery.value!!.deliveryId)
        runCurrent()
      }
      assertEquals(listOf(worktree to "/repo"), repository.moveCalls)
      assertEquals(2, repository.requests.size)
      assertEquals("/repo", (repository.requests.last().scope as ChatScope.Execution).repositoryRoot)
      assertTrue(repository.requests.last().images.single() === image)
      assertFalse(session.thread.hasDeliveryClaim())
      assertTrue(workspace.closeAll())
    }
  }

  @Test
  fun `late authoritative rejection restores the batch and workspace drains it after turn completion`() = runTest {
    chat.steeringPolicies["claude"] = ChatSteeringPolicy.LiveWhenAvailable
    chat.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "Awaiting acknowledgement") }
    val complete = CompletableDeferred<Unit>()
    chat.events = { flow { emit(ChatEvent.TextDelta("reply")); complete.await(); emit(ChatEvent.Completed) } }
    val active = workItem("SKILL-530").copy(repositoryRoot = "/repo")
    details.details = mapOf(active.id to MutableStateFlow(loaded(active)))
    val workspace = workspace()
    workspace.open(active)
    runCurrent()
    val session = workspace.session(active.id)!!
    workspace.send(active.id, "question")
    runCurrent()
    val image = ChatImage(byteArrayOf(8), "image/png")
    workspace.send(active.id, "rejected batch", listOf(image))
    workspace.sendNow(active.id)
    workspace.send(active.id, "later")
    runCurrent()
    val steering = chat.steeringRequests.single()
    assertTrue(session.thread.hasDeliveryClaim())

    complete.complete(Unit)
    runCurrent()
    chat.retainedSteering.value = mapOf((steering.sessionId to steering.deliveryId) to
      ChatSteeringUpdate(steering.sessionId, ChatSteeringResult.Rejected(steering.deliveryId, "turn already ended")))
    runCurrent()

    assertEquals(2, chat.requests.size)
    assertEquals("No execution changes since your last turn.\n\nquestion\n\nrejected batch\n\nlater", chat.requests.last().prompt)
    assertTrue(chat.requests.last().images.single() === image)
    assertFalse(session.thread.hasDeliveryClaim())
    assertEquals(null, session.thread.delivery.value)
    assertEquals(1, chat.retiredSteering.count { it.third == steering.deliveryId })
    assertTrue(workspace.closeAll())
  }

  @Test
  fun `removed worktree moves the sessions and a started claude session resumes at the primary root`() = runTest {
    val worktree = worktreeDir()
    val active = workItem("SKILL-468").copy(
      repositoryRoot = "/repo",
      goalWorktree = GoalWorktree(worktree, GoalWorktreeState.Active, null, null),
    )
    val detail = MutableStateFlow(loaded(active))
    details.details = mapOf(active.id to detail)
    val workspace = workspace()
    workspace.open(active)
    runCurrent()
    val session = workspace.session(active.id)!!
    val firstSessionId = session.sessionId
    assertEquals(worktree, (chat.requests.single().scope as ChatScope.Execution).repositoryRoot)

    val removed = active.copy(goalWorktree = active.goalWorktree?.copy(state = GoalWorktreeState.Removed))
    detail.value = loaded(removed)
    runCurrent()
    workspace.send(active.id, "continue")
    runCurrent()

    assertEquals(listOf(firstSessionId), chat.ended)
    assertEquals(listOf(worktree to "/repo"), chat.moveCalls)
    assertEquals("/repo", (chat.requests.last().scope as ChatScope.Execution).repositoryRoot)
    assertFalse(chat.requests.last().firstTurn)
    assertEquals(firstSessionId, chat.requests.last().sessionId)
    assertEquals(1, session.transcript.value.filterIsInstance<ChatMessage.Notice>().count {
      it.text == removedWorktreeNotice("/repo", SessionMoveResult(0, 0, 0))
    })
    workspace.closeAll()
  }

  @Test
  fun `verify fixes retain the original branch after its checkout is deleted and the chat is reopened`() = runTest {
    val checkout = worktreeDir()
    val item = workItem("SONA-1").copy(
      kind = WorkKind.Operation,
      runKindLabel = "verify",
      repositoryRoot = checkout,
      primaryRepositoryRoot = "/repo",
      branch = "feature/verified-work",
    )
    details.details = mapOf(item.id to MutableStateFlow(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    assertEquals(checkout, chat.requests.single().scope.repositoryRoot)

    Path.of(checkout).toFile().deleteRecursively()
    workspace.send(item.id, "Fix the verify findings directly")
    runCurrent()
    val scope = assertIs<ChatScope.Execution>(chat.requests.last().scope)
    assertEquals("/repo", scope.repositoryRoot)
    assertEquals("feature/verified-work", scope.verifySourceBranch)
    workspace.closeAll()

    val reopened = workspace()
    reopened.open(item)
    runCurrent()
    assertEquals("feature/verified-work", assertIs<ChatScope.Execution>(chat.requests.last().scope).verifySourceBranch)
    reopened.closeAll()
  }

  // Resuming a Claude session whose file stayed in the worktree's project directory would find no session in the repository root.
  @Test
  fun `a failed or throwing session move restarts a started claude session with a new id and says so`() = runTest {
    for (outcome in listOf<suspend (String, String) -> SessionMoveResult>({ _, _ -> SessionMoveResult(0, 0, 1) }, { _, _ -> error("disk") })) {
      val repository = FakeExecutionChatRepository().apply { moveResult = outcome }
      val active = workItem("SKILL-512").copy(
        repositoryRoot = "/repo",
        goalWorktree = GoalWorktree(worktreeDir(), GoalWorktreeState.Active, null, null),
      )
      val detail = MutableStateFlow(loaded(active))
      details.details = mapOf(active.id to detail)
      val workspace = workspace(repository = repository)
      workspace.open(active)
      runCurrent()
      val session = workspace.session(active.id)!!
      val firstSessionId = session.sessionId

      detail.value = loaded(active.copy(goalWorktree = active.goalWorktree?.copy(state = GoalWorktreeState.Removed)))
      runCurrent()
      workspace.send(active.id, "continue")
      runCurrent()

      assertEquals("/repo", (repository.requests.last().scope as ChatScope.Execution).repositoryRoot)
      assertTrue(repository.requests.last().firstTurn)
      assertTrue(repository.requests.last().sessionId != firstSessionId)
      assertEquals(1, session.transcript.value.filterIsInstance<ChatMessage.Notice>().count { it.text == "$REMOVED_WORKTREE_CHAT_NOTICE /repo. $MOVE_FAILED_SENTENCE" })
      workspace.closeAll()
    }
  }

  // Cursor chats are not moved, so a Cursor session still ends and restarts under a new id in the repository root.
  @Test
  fun `removed worktree restarts a cursor session with a new id at the primary root`() = runTest {
    val cursor = FakeExecutionChatRepository(ChatAgent("cursor", null, null), setOf("cursor"))
    val active = workItem("SKILL-512").copy(
      repositoryRoot = "/repo",
      goalWorktree = GoalWorktree(worktreeDir(), GoalWorktreeState.Active, null, null),
    )
    val detail = MutableStateFlow(loaded(active))
    details.details = mapOf(active.id to detail)
    val workspace = workspace(repository = cursor)
    workspace.open(active)
    runCurrent()
    val session = workspace.session(active.id)!!
    val firstSessionId = session.sessionId

    detail.value = loaded(active.copy(goalWorktree = active.goalWorktree?.copy(state = GoalWorktreeState.Removed)))
    runCurrent()
    workspace.send(active.id, "continue")
    runCurrent()

    assertEquals("/repo", (cursor.requests.last().scope as ChatScope.Execution).repositoryRoot)
    assertTrue(cursor.requests.last().firstTurn)
    assertTrue(cursor.requests.last().sessionId != firstSessionId)
    workspace.closeAll()
  }

  // The runtime row can still say Active after the worktree directory is deleted; the next turn must not run in the dead path.
  @Test
  fun `a deleted worktree directory under an Active row sends the next turn to the repository root`() = runTest {
    val worktree = worktreeDir()
    val active = workItem("SKILL-512").copy(
      repositoryRoot = "/repo",
      goalWorktree = GoalWorktree(worktree, GoalWorktreeState.Active, null, null),
    )
    details.details = mapOf(active.id to MutableStateFlow(loaded(active)))
    val workspace = workspace()
    workspace.open(active)
    runCurrent()
    val session = workspace.session(active.id)!!
    val firstSessionId = session.sessionId
    assertEquals(worktree, (chat.requests.single().scope as ChatScope.Execution).repositoryRoot)

    Path.of(worktree).toFile().deleteRecursively()
    workspace.send(active.id, "continue")
    runCurrent()

    assertEquals(listOf(firstSessionId), chat.ended)
    assertEquals("/repo", (chat.requests.last().scope as ChatScope.Execution).repositoryRoot)
    assertFalse(chat.requests.last().firstTurn)
    assertEquals(1, session.transcript.value.filterIsInstance<ChatMessage.Notice>().count { it.text.startsWith(REMOVED_WORKTREE_CHAT_NOTICE) })
    workspace.closeAll()
  }

  @Test
  fun `a removal and a failed main checkout in one refresh announce the removal first`() = runTest {
    val active = workItem("SKILL-512").copy(
      repositoryRoot = "/repo",
      goalWorktree = GoalWorktree(worktreeDir(), GoalWorktreeState.Active, null, null),
    )
    val detail = MutableStateFlow(loaded(active))
    details.details = mapOf(active.id to detail)
    val workspace = workspace()
    workspace.open(active)
    runCurrent()
    val session = workspace.session(active.id)!!

    detail.value = loaded(
      active.copy(
        goalWorktree = active.goalWorktree?.copy(
          state = GoalWorktreeState.Removed,
          mainCheckout = MainCheckoutOutcome.Failed,
          mainCheckoutDetail = "rebase in progress",
        ),
      ),
    )
    runCurrent()

    val notices = session.transcript.value.filterIsInstance<ChatMessage.Notice>().map { it.text }
    assertEquals(
      listOf(removedWorktreeNotice("/repo", SessionMoveResult(0, 0, 0)), "$MAIN_CHECKOUT_NOT_SWITCHED_NOTICE: rebase in progress."),
      notices.filter { it.startsWith(REMOVED_WORKTREE_CHAT_NOTICE) || it.startsWith(MAIN_CHECKOUT_NOT_SWITCHED_NOTICE) },
    )
    workspace.closeAll()
  }

  @Test
  fun `a main checkout outcome delivered after the removal notice is announced once`() = runTest {
    val active = workItem("SKILL-512").copy(
      repositoryRoot = "/repo",
      goalWorktree = GoalWorktree(worktreeDir(), GoalWorktreeState.Active, null, null),
    )
    val detail = MutableStateFlow(loaded(active))
    details.details = mapOf(active.id to detail)
    val workspace = workspace()
    workspace.open(active)
    runCurrent()
    val session = workspace.session(active.id)!!

    val removed = active.copy(goalWorktree = active.goalWorktree?.copy(state = GoalWorktreeState.Removed))
    detail.value = loaded(removed)
    runCurrent()
    val skipped = removed.copy(
      goalWorktree = removed.goalWorktree?.copy(
        mainCheckout = MainCheckoutOutcome.SkippedUncommittedChanges,
        mainCheckoutDetail = "app/build.gradle",
      ),
    )
    detail.value = loaded(skipped)
    runCurrent()
    detail.value = loaded(skipped.copy(title = "refreshed"))
    runCurrent()

    val notices = session.transcript.value.filterIsInstance<ChatMessage.Notice>().map { it.text }
    val expected = "$MAIN_CHECKOUT_NOT_SWITCHED_NOTICE: app/build.gradle."
    assertEquals(1, notices.count { it == expected })
    val removedNotice = notices.indexOf(removedWorktreeNotice("/repo", SessionMoveResult(0, 0, 0)))
    assertTrue(removedNotice >= 0, "the removed-worktree notice is missing: $notices")
    assertTrue(removedNotice < notices.indexOf(expected))
    workspace.closeAll()
  }

  @Test
  fun `an image-only message is queued and its images reach the turn request while text-only turns carry none`() = runTest {
    val item = workItem("SKILL-450")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val image = ChatImage(byteArrayOf(1, 2, 3), "image/png")

    workspace.send(item.id, "", listOf(image))
    runCurrent()
    assertEquals(listOf(image), chat.requests.last().images)
    assertEquals(listOf(image), session.transcript.value.filterIsInstance<ChatMessage.Operator>().single().images)

    workspace.send(item.id, "text only")
    runCurrent()
    assertEquals(emptyList(), chat.requests.last().images)
    workspace.closeAll()
  }

  @Test
  fun `a refused draft restores its own MCP declaration after the picker changes`() = runTest {
    val item = workItem("SKILL-453")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val reply = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { flow { for (event in reply) emit(event) } }

    workspace.send(item.id, "use the team server", cursorMcpDependency = CursorMcpDependency.TeamDashboardRequired)
    runCurrent()
    workspace.cursorMcpDependencies[item.id] = CursorMcpDependency.ProjectUserOnly
    reply.send(ChatEvent.Failed(ChatFailure.AgentUnsupported, null))
    reply.close()
    runCurrent()

    assertEquals("use the team server", workspace.drafts[item.id])
    assertEquals(CursorMcpDependency.TeamDashboardRequired, workspace.cursorMcpDependencies[item.id])
    workspace.closeAll()
  }

  @Test
  fun `an image that finishes normalizing after its chat closed is dropped`() = runTest {
    val item = workItem("SKILL-450")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val workspace = workspace()
    workspace.open(item)
    runCurrent()

    workspace.close(item.id)
    workspace.addPastedImage(item.id, ChatImage(byteArrayOf(1), "image/png"))

    assertNull(workspace.pastedImages[item.id])
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
    assertTrue(
      request.prompt.contains("sona --db '/selected database.db' prepare-review --invocation-id 'original-review'"),
    )
    assertTrue(request.prompt.contains("Use the returned review_run_id."))
    assertTrue(request.prompt.contains("start-review-action --run-id <review_run_id> --action verify --format json"))
    assertTrue(request.prompt.contains("Retain its action_id."))
    assertTrue(request.prompt.contains("record-feedback --run-id <review_run_id> --event fix_applied"))
    assertTrue(
      request.prompt.contains("finish-review-action --run-id <review_run_id> --action-id <action_id> --format json"),
    )
    assertTrue(request.prompt.contains("Pass --failed if you could not finish the requested work."))
    workspace.closeAll()
  }

  @Test
  fun `queued messages and images send as one follow-up without splitting the streaming answer`() = runTest {
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

    val firstImage = ChatImage(byteArrayOf(1), "image/png")
    val secondImage = ChatImage(byteArrayOf(2), "image/png")
    workspace.send(item.id, "first question", listOf(firstImage))
    workspace.send(item.id, "second question")
    workspace.send(item.id, "", listOf(secondImage))
    runCurrent()
    assertEquals(1, chat.requests.size)
    assertEquals(3, session.queued.value.size)
    replies[0].send(ChatEvent.TextDelta("after"))
    replies[0].send(ChatEvent.Completed)
    replies[0].close()
    runCurrent()

    assertEquals(2, chat.requests.size)
    val combined = "first question\n\nsecond question"
    assertTrue(chat.requests[1].prompt.endsWith(combined))
    assertEquals(listOf(firstImage, secondImage), chat.requests[1].images)
    assertEquals("Before after", session.transcript.value.filterIsInstance<ChatMessage.Agent>().first().text)
    assertEquals(listOf(combined), session.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.text })
    assertEquals(listOf(firstImage, secondImage), session.transcript.value.filterIsInstance<ChatMessage.Operator>().single().images)
    assertTrue(session.queued.value.isEmpty())
    workspace.send(item.id, "later question")
    runCurrent()
    assertEquals(2, chat.requests.size)
    assertEquals(1, session.queued.value.size)
    replies[1].send(ChatEvent.Failed(ChatFailure.BinaryMissing, null))
    replies[1].close()
    runCurrent()

    assertEquals(3, chat.requests.size)
    assertTrue(chat.requests[2].prompt.endsWith("later question"))
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

  // Summarizing on every reply bills the operator per turn; missing the switch or close leaves rows on fallback titles.
  @Test
  fun `switching away from an idle chat and closing one request summaries and a finished turn does not`() = runTest {
    val goal = workItem("SKILL-1")
    val phase = workItem("phase-1", kind = WorkKind.Phase)
    details.details = mapOf(goal.id to flowOf(loaded(goal)), phase.id to flowOf(loaded(phase)))
    val workspace = workspace()

    workspace.open(goal)
    workspace.open(phase)
    runCurrent()
    assertEquals(emptyList(), chat.summaryRequests)

    workspace.activate(goal.id)
    val goalSessionId = workspace.session(goal.id)!!.sessionId
    workspace.close(goal.id)
    runCurrent()

    assertEquals(listOf(workspace.session(phase.id)!!.sessionId, goalSessionId), chat.summaryRequests)
  }

  // Summarizing a chat mid-turn bills a summary of half a reply and leaves the finished chat stale.
  @Test
  fun `switching away from a busy chat requests no summary`() = runTest {
    val goal = workItem("SKILL-1")
    val phase = workItem("phase-1", kind = WorkKind.Phase)
    details.details = mapOf(goal.id to flowOf(loaded(goal)), phase.id to flowOf(loaded(phase)))
    chat.events = { flow<ChatEvent> { awaitCancellation() } }
    val workspace = workspace()

    workspace.open(goal)
    workspace.open(phase)
    runCurrent()
    workspace.activate(goal.id)
    runCurrent()

    assertTrue(workspace.session(phase.id)!!.busyState.value)
    assertEquals(emptyList(), chat.summaryRequests)
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
  fun `toggleChatPane with leftover selection and no tabs only flips pane visibility`() = runTest {
    val item = workItem("SKILL-473")
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val workspace = workspace()
    selection.select(item)

    workspace.toggleChatPane()
    assertTrue(workspace.paneOpen.value)
    assertTrue(panes.chatOpen)
    assertTrue(workspace.tabs.value.tabs.isEmpty())
    assertTrue(chat.requests.isEmpty())

    workspace.toggleChatPane()
    assertFalse(workspace.paneOpen.value)
    assertFalse(panes.chatOpen)
    assertTrue(workspace.tabs.value.tabs.isEmpty())
    assertTrue(chat.requests.isEmpty())
  }

  @Test
  fun `toggleChatPane hide then show restores the same tabs without opening leftover selection`() = runTest {
    val goal = workItem("SKILL-1")
    val other = workItem("SKILL-2")
    details.details = mapOf(goal.id to flowOf(loaded(goal)), other.id to flowOf(loaded(other)))
    val cancelled = mutableListOf<String>()
    chat.events = { request -> flow<ChatEvent> { try { awaitCancellation() } finally { cancelled += request.executionId } } }
    val workspace = workspace()
    workspace.open(goal)
    runCurrent()
    val tabsBefore = workspace.tabs.value
    val requestsBefore = chat.requests.size
    val session = workspace.session(goal.id)!!

    selection.select(other)
    workspace.toggleChatPane()
    assertFalse(workspace.paneOpen.value)
    assertTrue(session.job.isActive)
    assertTrue(cancelled.isEmpty())
    workspace.toggleChatPane()
    runCurrent()

    assertTrue(workspace.paneOpen.value)
    assertEquals(tabsBefore.tabs.map { it.id }, workspace.tabs.value.tabs.map { it.id })
    assertEquals(tabsBefore.activeId, workspace.tabs.value.activeId)
    assertEquals(requestsBefore, chat.requests.size)
    assertTrue(workspace.tabs.value.tabs.none { it.executionId == other.id })
    assertTrue(session.job.isActive)
    assertTrue(cancelled.isEmpty())
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
  fun `chat turns use Act and fresh provider sessions retain execution context across follow-ups`() = runTest {
    val agents = setOf("claude", "codex", "cursor")
    for (agent in agents) {
      for (lifecycle in Lifecycle.entries) {
        val item = workItem("SKILL-1", lifecycle = lifecycle).copy(running = lifecycle == Lifecycle.Active, agentId = agent)
        details.details = mapOf(item.id to flowOf(loaded(item)))
        val repository = FakeExecutionChatRepository(ChatAgent(agent, null, null), agents)
        val workspace = workspace(repository = repository)

        workspace.open(item)
        runCurrent()
        workspace.send(item.id, "edit the requested file")
        runCurrent()

        assertEquals(2, repository.requests.size, "$agent / $lifecycle")
        repository.requests.forEach { request ->
          assertEquals(ChatPermissionMode.Act, request.mode, "$agent / $lifecycle")
          assertEquals(agent, request.agent.agentId)
        }
        val followUp = repository.requests.last()
        assertFalse(followUp.firstTurn, "$agent / $lifecycle")
        assertTrue(followUp.prompt.startsWith("No execution changes since your last turn."), followUp.prompt)
        assertTrue(controls.calls.isEmpty())
        workspace.close(item.id)
        runCurrent()
      }
    }
  }

  @Test
  fun `execution chat sends an adjustment reply to a replacement verify proposal instead of an ordinary agent turn`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Terminal).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    val original = OperationCardView(
      "old-token", "old-launch", "verify", OperationCardState.AwaitingConfirmation, "Original proposal",
      OperationCardDetailsView.Verify(emptyList(), "SKILL-1", "/repo/spec.md", "branch", "local", "full"),
      null, null, canConfirm = true,
    )
    val revised = original.copy(token = "new-token", launchId = "new-launch", summary = "Revised proposal")
    controls.operationCards[original.launchId] = original
    controls.operationCards[revised.launchId] = revised
    controls.newTaskOutcome = NewTaskStartOutcome.AwaitingConfirmation("SKILL-1", NewTaskKind.Verify, original.launchId)
    val action = ChatSuggestedAction("Verify", "Verify this branch", ChatPermissionMode.Act, NewTaskActionKind.Verify, target = "spec:/repo/spec.md")
    chat.events = { flowOf(ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed) }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val reply = session.transcript.value.filterIsInstance<ChatMessage.Agent>().last()
    workspace.executeAction(item.id, reply, action)
    runCurrent()
    workspace.operationCardActions(item.id)!!.onAdjust(original.token)
    runCurrent()
    assertEquals(1, controls.newTaskRequests.size)

    controls.newTaskOutcome = NewTaskStartOutcome.AwaitingConfirmation("SKILL-1", NewTaskKind.Verify, revised.launchId)
    workspace.send(item.id, "Focus on accessibility")
    runCurrent()

    assertEquals(1, chat.requests.size)
    assertEquals("Focus on accessibility", controls.newTaskRequests.last().proposalChanges)
    assertEquals(revised, session.thread.operationCard(revised.token)?.card)
  }

  @Test
  fun `a parked verify run's chat shows its card and Adjust rebuilds the request from the run`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Terminal).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item, emptyDetail.copy(verifyRun = parkedVerify(item.id)))))
    val card = parkedVerifyCard(item.id)
    val revised = card.copy(token = "revised-token", launchId = "revised-launch")
    controls.operationCards[card.launchId] = card
    controls.operationCards[revised.launchId] = revised
    val workspace = workspace()

    workspace.openParkedVerify(item, adjust = true)
    runCurrent()
    val session = workspace.session(item.id)!!
    assertEquals(card, session.thread.operationCard(card.token)?.card)
    assertEquals(0, chat.requests.size)

    controls.newTaskOutcome = NewTaskStartOutcome.AwaitingConfirmation("SKILL-1", NewTaskKind.Verify, revised.launchId)
    workspace.send(item.id, "Drop the second criterion")
    runCurrent()

    val sent = controls.newTaskRequests.single()
    assertEquals("spec:/repo/spec.md", sent.target)
    assertEquals("feat/skill-1", sent.branch)
    assertEquals("Drop the second criterion", sent.proposalChanges)
    assertEquals(VerifyStartChoices(VerifyQaChoice.On, VerifyReviewModeChoice.Delegated), sent.verify)
    assertNull(sent.confirmation)
    assertEquals(0, chat.requests.size)
    assertEquals(revised, session.thread.operationCard(revised.token)?.card)
  }

  @Test
  fun `a run chat adjusts a verify card with the request of the New task chat that started it`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Terminal).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item, emptyDetail.copy(verifyRun = parkedVerify(item.id)))))
    val card = parkedVerifyCard(item.id)
    controls.operationCards[card.launchId] = card
    val chats = unconfinedNewTaskChats()
    val started = NewTaskStart("/repo", "", null, "codex", NewTaskKind.Verify, null, null, target = "WE-1", branch = "feat/we-1")
    chats.cardRequests.remember(card.launchId, started)
    val workspace = workspace(chats)

    workspace.openParkedVerify(item, adjust = true)
    runCurrent()
    controls.newTaskOutcome = NewTaskStartOutcome.Refused("unused")
    workspace.send(item.id, "Only the first criterion")
    runCurrent()

    assertEquals(started.copy(proposalChanges = "Only the first criterion"), controls.newTaskRequests.single())
  }

  @Test
  fun `a run chat that is not a parked verify shows no card`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Terminal).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    controls.operationCards["verify-launch"] = parkedVerifyCard(item.id)
    val workspace = workspace()

    workspace.open(item)
    runCurrent()

    assertNull(workspace.session(item.id)!!.thread.operationCard(item.id))
  }

  private fun parkedVerifyCard(token: String) = OperationCardView(
    token, "verify-launch", "verify", OperationCardState.AwaitingConfirmation, "Proposal",
    OperationCardDetailsView.Verify(listOf("1. Works"), "spec:/repo/spec.md", "/repo/spec.md", "feat/skill-1", "delegated", "on"),
    null, null, canConfirm = true,
  )

  private fun parkedVerify(workflowId: String) = VerifyRunDetails(
    workflowId = workflowId, issueKey = "SKILL-1", workflowStatus = "pending", currentStepId = "extract_criteria",
    startedAt = null, finishedAt = null, intake = "spec:/repo/spec.md", target = "feat/skill-1", repoRoot = "/repo",
    baseRevision = null, headRevision = null, reviewMode = "delegated", qaSelection = "on", specPath = "/repo/spec.md",
    steps = emptyList(), criteria = emptyList(), criteriaRaw = null, nonGoals = emptyList(), technicalConstraints = emptyList(),
    rolloutExpectation = null, completenessAudit = null, reviewFindings = null, flagAudit = null, unitTestValue = null,
    changedFiles = emptyList(), verdict = null, qa = null, qaVerdict = null, qaOutcome = null, qaFindingLocations = emptyMap(),
    supersededBy = null, staleReason = null,
  )

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
    assertEquals(ChatPermissionMode.Act, chat.requests.last().mode)
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
    chat.events = { request -> if (request.prompt.startsWith(ACT_PREFIX)) flow<ChatEvent> { awaitCancellation() } else flowOf(ChatEvent.Completed) }
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
      if (request.prompt.startsWith(ACT_PREFIX)) {
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
      if (request.prompt.startsWith(ACT_PREFIX)) {
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
    assertTrue(session.transcript.value.any { it is ChatMessage.Notice && "Codex · gpt-5 · high" in it.text })
  }

  // The picker commits agent and model together; dropping the pick would start the new agent on its matrix model instead.
  @Test
  fun `choosing another agent with a model switches once and starts on that model`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(running = false)
    details.details = mapOf(item.id to flowOf(loaded(item)))
    chat.agentFor = { ChatAgent(it, "gpt-5", "high") }
    val workspace = workspace()
    workspace.open(item)
    runCurrent()
    val session = workspace.session(item.id)!!
    val notices = session.transcript.value.count { it is ChatMessage.Notice }

    workspace.choose(item.id, "codex", ModelPick("gpt-6", null, "GPT-6"))
    runCurrent()
    workspace.send(item.id, "what failed?")
    runCurrent()

    assertEquals(ChatAgent("codex", "gpt-6", null), chat.requests.last().agent)
    assertTrue(chat.requests.last().firstTurn)
    val added = session.transcript.value.filterIsInstance<ChatMessage.Notice>().drop(notices)
    assertEquals(1, added.size)
    assertTrue("Codex · gpt-6" in added.single().text)
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
    assertEquals(listOf(saved), session.resumeChoices.value?.entries)

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
  fun `start action on stopped execution runs in the goal worktree and names the main repository`() = runTest {
    val worktree = worktreeDir()
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(
      running = false,
      repositoryRoot = "/work/repo",
      goalWorktree = GoalWorktree(worktree, GoalWorktreeState.Active, null, null),
    )
    details.details = mapOf(item.id to flowOf(loaded(item)))
    controls.newTaskOutcome = NewTaskStartOutcome.Started("verify", NewTaskKind.Verify)
    val action = ChatSuggestedAction(
      "Run operation:verify",
      "Verify the branch.",
      ChatPermissionMode.Act,
      NewTaskActionKind.Verify,
      target = "SKILL-1",
      branch = "feat/fp-5175",
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
      assertEquals("/work/repo", repoRoot)
      assertEquals(worktree, checkoutRoot)
      assertEquals(NewTaskKind.Verify, kind)
      assertEquals("SKILL-1", target)
      assertEquals("feat/fp-5175", branch)
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
      chat, details, controls, closedDb, WindowCoroutineScope(backgroundScope), clock, panes, selection, unconfinedNewTaskChats(), diagnostics = RecordingDiagnostics(),
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
