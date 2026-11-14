package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
import dev.skillbill.desktop.core.testing.FakeChatChangesRepository
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.OperationCardDetailsView
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.OperationCardView
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.ChatWorkspace
import dev.skillbill.desktop.feature.chat.STOPPED_NOTICE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private const val REPLAY_NOTICE = "It gets the earlier conversation with your next message."

/** A draft for tests that never send. */
internal fun testDatabaseSessionController(sessionId: Long? = 1L): DatabaseSessionController = object : DatabaseSessionController {
  override val state = MutableStateFlow<DatabaseSessionState>(
    sessionId?.let { DatabaseSessionState.Open("/db", it) } ?: DatabaseSessionState.Opening(null),
  ).asStateFlow()

  override fun reopen() = Unit
}

internal fun testNewTaskDraft() = NewTaskDraft(
  FakeExecutionChatRepository(), FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
  WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskDraftTest {
  private val chat = FakeExecutionChatRepository(supported = setOf("claude", "codex", "cursor"))
  private val root = createTempDirectory("new-task-repo").toString()

  private fun TestScope.draft(chats: ExecutionChatRepository = chat, controls: FakeWorkControlRepository = FakeWorkControlRepository(), changes: ChatChangesRepository = UnavailableChatChanges) = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, changesRepository = changes, diagnostics = RecordingDiagnostics()).apply {
    chatDispatcher = StandardTestDispatcher(testScheduler)
    repository = NewTaskRepository(root, "repo")
    baseBranch = "main"
    agentId = "claude"
    selectedAgent = this@NewTaskDraftTest.chat.agent
  }

  @Test
  fun `a reply flow closed without a terminal event leaves no streaming segment`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("Partial reply")) }
    val draft = draft()
    draft.say("question")
    runCurrent()
    val thread = draft.chat.value!!.thread
    val reply = thread.transcript.value.filterIsInstance<ChatMessage.Agent>().single()
    assertEquals("Partial reply", reply.text)
    assertFalse(reply.streaming)
    assertFalse(thread.busy.value)
    draft.dispose()
  }

  @Test
  fun `draft flush rebind and disposal keep unresolved originals reachable until warning confirmed retry`() = runTest {
    chat.steeringPolicies["claude"] = ChatSteeringPolicy.LiveWhenAvailable
    chat.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "Awaiting acknowledgement") }
    var turns = 0
    chat.events = {
      turns++
      if (turns == 1) flow { emit(ChatEvent.TextDelta("reply")); awaitCancellation() } else flowOf(ChatEvent.Completed)
    }
    val changes = FakeChatChangesRepository(ChatBaselineCapture.Captured(ChatChangesBaseline("head", emptyList(), null, Clock.System.now())))
    val draft = draft(changes = changes)
    draft.say("question")
    runCurrent()
    val current = draft.chat.value!!
    val image = ChatImage(byteArrayOf(3), "image/png")
    draft.text = "retained"
    draft.addPastedImage(image)
    draft.send()
    draft.sendNow()
    runCurrent()
    val deliveryId = current.thread.delivery.value!!.deliveryId
    draft.say("ordinary")
    draft.stop()
    runCurrent()
    assertEquals("ordinary", draft.text)
    assertEquals("retained", current.thread.delivery.value!!.text)
    assertFalse(current.flush())
    assertFalse(draft.close())
    assertFalse(draft.discard())
    draft.baseBranch = "other"
    draft.rebindChat()
    assertTrue(draft.chat.value === current)
    assertEquals("main", current.baseBranch)
    draft.dispose()
    runCurrent()
    assertTrue(draft.chat.value === current)
    assertTrue(current.thread.delivery.value!!.images.single() === image)
    assertEquals(1, chat.requests.size)
    assertTrue(chat.ended.contains(current.sessionId))
    val readsBeforeRetry = changes.currentReadCalls.size
    draft.say("later")
    current.retryDelivery(deliveryId)
    runCurrent()
    assertEquals(2, chat.requests.size)
    assertTrue(changes.currentReadCalls.size > readsBeforeRetry)
    assertEquals("retained\n\nlater", chat.requests.last().prompt)
    assertEquals("main", (chat.requests.last().scope as ChatScope.NewTask).baseBranch)
    assertTrue(chat.requests.last().images.single() === image)
    chat.retainedSteering.value = mapOf((current.sessionId to deliveryId) to
      ChatSteeringUpdate(current.sessionId, ChatSteeringResult.Accepted(deliveryId)))
    runCurrent()
    assertEquals(2, chat.requests.size)
    assertEquals(1, chat.retiredSteering.count { it.third == deliveryId })
  }

  @Test
  fun `discard delivery after direct draft disposal permits final close without replay`() = runTest {
    chat.steeringPolicies["claude"] = ChatSteeringPolicy.LiveWhenAvailable
    chat.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "Awaiting acknowledgement") }
    chat.events = { flow { emit(ChatEvent.TextDelta("reply")); awaitCancellation() } }
    val draft = draft()
    draft.say("question")
    runCurrent()
    draft.say("retained")
    draft.sendNow()
    runCurrent()
    val current = draft.chat.value!!
    val deliveryId = current.thread.delivery.value!!.deliveryId
    draft.dispose()
    runCurrent()
    current.discardDelivery(deliveryId)
    runCurrent()
    assertFalse(current.thread.hasDeliveryClaim())
    assertTrue(draft.close())
    runCurrent()
    assertEquals(null, draft.chat.value)
    assertEquals(1, chat.requests.size)
  }

  @Test
  fun `live draft delivery retains uncertainty through completion and late acknowledgement`() = runTest {
    chat.steeringPolicies["claude"] = ChatSteeringPolicy.LiveWhenAvailable
    chat.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "Awaiting acknowledgement") }
    val complete = CompletableDeferred<Unit>()
    chat.events = { flow { emit(ChatEvent.TextDelta("Before")); complete.await(); emit(ChatEvent.Completed) } }
    val draft = draft()
    draft.say("question")
    runCurrent()
    draft.say("guidance")
    draft.sendNow()
    draft.sendNow()
    draft.say("later")
    runCurrent()
    val current = draft.chat.value!!
    assertEquals(1, chat.steeringRequests.size)
    assertEquals(1, chat.requests.size)
    assertTrue(chat.ended.isEmpty())
    complete.complete(Unit)
    runCurrent()
    assertEquals(1, chat.requests.size)
    assertTrue(current.thread.hasDeliveryClaim())
    val request = chat.steeringRequests.single()
    chat.retainedSteering.value = mapOf((request.sessionId to request.deliveryId) to
      ChatSteeringUpdate(request.sessionId, ChatSteeringResult.Accepted(request.deliveryId)))
    runCurrent()
    assertFalse(current.thread.hasDeliveryClaim())
    assertEquals(2, chat.requests.size)
    assertEquals(1, current.thread.transcript.value.filterIsInstance<ChatMessage.Operator>().count { it.text == "guidance" })
    assertEquals("later", chat.requests.last().prompt.takeLast(5))
  }

  @Test
  fun `live draft delivery resumes queued work after an authoritative late rejection`() = runTest {
    chat.steeringPolicies["claude"] = ChatSteeringPolicy.LiveWhenAvailable
    chat.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "Awaiting acknowledgement") }
    var turns = 0
    val finishFirst = CompletableDeferred<Unit>()
    chat.events = {
      turns++
      if (turns == 1) flow {
        emit(ChatEvent.TextDelta("reply"))
        finishFirst.await()
        emit(ChatEvent.Completed)
      } else flowOf(ChatEvent.Completed)
    }
    val draft = draft()
    draft.say("question")
    runCurrent()
    val current = draft.chat.value!!
    val image = ChatImage(byteArrayOf(4), "image/png")
    draft.text = "rejected batch"
    draft.addPastedImage(image)
    draft.send()
    draft.sendNow()
    draft.say("later")
    runCurrent()
    val request = chat.steeringRequests.single()
    assertTrue(current.thread.hasDeliveryClaim())
    finishFirst.complete(Unit)
    runCurrent()

    chat.retainedSteering.value = mapOf((request.sessionId to request.deliveryId) to
      ChatSteeringUpdate(request.sessionId, ChatSteeringResult.Rejected(request.deliveryId, "turn already ended")))
    runCurrent()

    assertEquals(2, chat.requests.size)
    assertEquals("rejected batch\n\nlater", chat.requests.last().prompt)
    assertTrue(chat.requests.last().images.single() === image)
    assertFalse(current.thread.hasDeliveryClaim())
    assertEquals(null, current.thread.delivery.value)
    assertEquals(1, chat.retiredSteering.count { it.third == request.deliveryId })
    assertEquals(listOf("question", "rejected batch\n\nlater"),
      current.thread.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.text })
  }

  @Test
  fun `new task teardown retains ownership until cancellation cleanup publishes rejection`() = runTest {
    chat.steeringPolicies["claude"] = ChatSteeringPolicy.LiveWhenAvailable
    chat.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "Awaiting acknowledgement") }
    chat.events = { flow { emit(ChatEvent.TextDelta("reply")); awaitCancellation() } }
    val cleanupStarted = CompletableDeferred<Unit>()
    val cleanupFinished = CompletableDeferred<Unit>()
    chat.awaitEndAction = {
      cleanupStarted.complete(Unit)
      cleanupFinished.await()
    }
    val draft = draft()
    draft.say("question")
    runCurrent()
    val current = checkNotNull(draft.chat.value)
    val image = ChatImage(byteArrayOf(6), "image/png")
    draft.text = "rejected batch"
    draft.addPastedImage(image)
    draft.send()
    draft.sendNow()
    runCurrent()
    val request = chat.steeringRequests.single()
    val ending = async { current.end() }
    cleanupStarted.await()

    chat.retainedSteering.value = mapOf((request.sessionId to request.deliveryId) to
      ChatSteeringUpdate(request.sessionId, ChatSteeringResult.Rejected(request.deliveryId, "turn already ended")))
    runCurrent()
    assertTrue(current.thread.hasDeliveryClaim())
    assertTrue(chat.retiredSteering.none { it.third == request.deliveryId })

    cleanupFinished.complete(Unit)
    assertFalse(ending.await())
    assertEquals(listOf("rejected batch"), current.thread.queued.value.map { it.text })
    assertTrue(current.thread.queued.value.single().images.single() === image)
    assertFalse(current.thread.hasDeliveryClaim())
    assertEquals(1, chat.retiredSteering.count { it.third == request.deliveryId })
  }

  @Test
  fun `live draft fallback during unprompted output waits for normal completion without interruption`() = runTest {
    chat.steeringPolicies["claude"] = ChatSteeringPolicy.LiveWhenAvailable
    chat.steeringResult = { ChatSteeringResult.NoActiveTurn(it.deliveryId, "No active reply") }
    val draft = draft()
    draft.say("question")
    runCurrent()
    val current = draft.chat.value!!
    chat.unpromptedEvents.emit(current.sessionId to ChatEvent.TextDelta("Background output"))
    runCurrent()
    draft.say("guidance")
    draft.sendNow()
    runCurrent()
    assertEquals(1, chat.steeringRequests.size)
    assertEquals(listOf("guidance"), current.thread.queued.value.map { it.text })
    assertEquals(1, chat.requests.size)
    assertTrue(chat.ended.isEmpty())
    chat.unpromptedEvents.emit(current.sessionId to ChatEvent.Completed)
    runCurrent()
    assertEquals(2, chat.requests.size)
    assertTrue(current.thread.queued.value.isEmpty())
  }

  private fun NewTaskDraft.say(message: String) {
    text = message
    send()
  }

  @Test
  fun `new task chat answers Adjust with a revised verify card without sending a normal agent turn`() = runTest {
    val original = OperationCardView(
      "old-token", "old-launch", "verify", OperationCardState.AwaitingConfirmation, "Original proposal",
      OperationCardDetailsView.Verify(emptyList(), "SKILL-1", "/repo/spec.md", "branch", "local", "full"),
      null, null, canConfirm = true,
    )
    val revised = original.copy(token = "new-token", launchId = "new-launch", summary = "Revised proposal")
    val controls = FakeWorkControlRepository().apply {
      operationCards[original.launchId] = original
      operationCards[revised.launchId] = revised
      newTaskOutcome = NewTaskStartOutcome.AwaitingConfirmation("SKILL-1", NewTaskKind.Verify, original.launchId)
    }
    val draft = draft(controls = controls)
    draft.say("Verify this branch")
    runCurrent()
    draft.start(NewTaskKind.Verify, target = "spec:/repo/spec.md")
    runCurrent()
    val current = checkNotNull(draft.chat.value)
    draft.operationCardActions(current).onAdjust(original.token)
    runCurrent()
    assertEquals(1, controls.newTaskRequests.size)

    controls.newTaskOutcome = NewTaskStartOutcome.AwaitingConfirmation("SKILL-1", NewTaskKind.Verify, revised.launchId)
    draft.say("Exclude deployment")
    runCurrent()

    assertEquals(1, chat.requests.size)
    assertEquals("Exclude deployment", controls.newTaskRequests.last().proposalChanges)
    assertEquals(revised, current.thread.operationCard(revised.token)?.card)
    assertEquals("", draft.text)
  }

  @Test
  fun `stopping returns queued images to the draft and resending delivers each attachment once`() = runTest {
    chat.events = { flow { awaitCancellation() } }
    val draft = draft()
    draft.say("first")
    runCurrent()
    val queued = ChatImage(byteArrayOf(1), "image/png")
    val pending = ChatImage(byteArrayOf(2), "image/png")
    draft.addPastedImage(queued)
    draft.send()
    draft.addPastedImage(pending)
    draft.stop()
    runCurrent()

    assertEquals(listOf(queued, pending), draft.images)
    assertTrue(draft.canSend())
    draft.send()
    runCurrent()

    assertEquals(listOf(queued, pending), chat.requests.last().images)
    assertEquals(2, chat.requests.size)
    assertTrue(draft.images.isEmpty())
    draft.close()
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

  @Test
  fun `saved chat discard hides immediately and each undo window settles independently`() = runTest {
    val toasts = ToastQueue()
    val first = ChatHistoryEntry(
      "first-discard", null, requireNotNull(chat.agent), Instant.fromEpochSeconds(1), "Fix header",
      listOf(ChatHistoryLine(ChatHistoryRole.Operator, "Fix header")), ChatNewTaskContext(root, "main"),
    )
    val second = first.copy(sessionId = "second-discard", title = "Fix footer")
    chat.saved[first.sessionId] = first
    chat.saved[second.sessionId] = second
    val draft = NewTaskDraft(chat, FakeWorkControlRepository(), testDatabaseSessionController(), toasts, WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics())

    draft.refreshSaved()
    runCurrent()
    assertEquals(listOf(first, second), draft.saved.value.entries)
    draft.discardSaved(first)
    draft.discardSaved(second)
    draft.discardSaved(first)
    runCurrent()
    draft.refreshSaved()
    runCurrent()

    assertTrue(first.sessionId in chat.saved)
    assertTrue(second.sessionId in chat.saved)
    assertTrue(draft.saved.value.entries.isEmpty())
    assertEquals(2, toasts.host.toasts.size)
    assertEquals("Discarded Fix footer", toasts.host.toasts.last().data.title)
    advanceTimeBy(4_999)
    runCurrent()
    assertTrue(first.sessionId in chat.saved)
    assertTrue(second.sessionId in chat.saved)
    toasts.host.toasts.last().data.actions.single().onClick()
    runCurrent()
    assertEquals(listOf(second), draft.saved.value.entries)

    advanceTimeBy(1)
    runCurrent()

    assertTrue(first.sessionId !in chat.saved)
    assertTrue(second.sessionId in chat.saved)
    assertEquals(listOf(second), draft.saved.value.entries)
  }

  @Test
  fun `refresh cannot restore a discard while its delete is suspended and expired undo cannot cancel it`() = runTest {
    val entry = ChatHistoryEntry(
      "deleting-discard", null, requireNotNull(chat.agent), Instant.fromEpochSeconds(1), "Fix header",
      listOf(ChatHistoryLine(ChatHistoryRole.Operator, "Fix header")), ChatNewTaskContext(root, "main"),
    )
    chat.saved[entry.sessionId] = entry
    val deleting = CompletableDeferred<Unit>()
    val completeDelete = CompletableDeferred<Unit>()
    val repository = object : ExecutionChatRepository by chat {
      override suspend fun deleteSavedChat(sessionId: String) {
        deleting.complete(Unit)
        completeDelete.await()
        chat.deleteSavedChat(sessionId)
      }
    }
    val toasts = ToastQueue()
    val draft = NewTaskDraft(repository, FakeWorkControlRepository(), testDatabaseSessionController(), toasts, WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics())
    draft.refreshSaved()
    runCurrent()
    assertEquals(listOf(entry), draft.saved.value.entries)

    draft.discardSaved(entry)
    runCurrent()
    advanceTimeBy(5_000)
    runCurrent()
    assertTrue(deleting.isCompleted)
    draft.refreshSaved()
    runCurrent()
    assertTrue(draft.saved.value.entries.isEmpty())
    assertTrue(entry.sessionId in chat.saved)

    toasts.host.toasts.single().data.actions.single().onClick()
    runCurrent()
    assertTrue(draft.saved.value.entries.isEmpty())
    completeDelete.complete(Unit)
    runCurrent()
    assertTrue(entry.sessionId !in chat.saved)
    assertTrue(draft.saved.value.entries.isEmpty())
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

  @Test
  fun `a blank base branch reaches the new task chat as the default`() = runTest {
    val draft = draft().apply { baseBranch = "" }
    draft.say("add export")
    runCurrent()

    assertEquals(ChatScope.NewTask(root, null), chat.requests.single().scope)
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
  fun `a refused new task restores the submitted MCP declaration with its text`() = runTest {
    val reply = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { flow { for (event in reply) emit(event) } }
    val draft = draft()
    draft.cursorMcpDependency = CursorMcpDependency.TeamDashboardRequired
    draft.say("use the team server")
    runCurrent()
    draft.cursorMcpDependency = CursorMcpDependency.ProjectUserOnly

    reply.send(ChatEvent.Failed(ChatFailure.AgentUnsupported, null))
    reply.close()
    runCurrent()

    assertEquals("use the team server", draft.text)
    assertEquals(CursorMcpDependency.TeamDashboardRequired, draft.cursorMcpDependency)
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

    val next = NewTaskDraft(chat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics())
    next.chatDispatcher = StandardTestDispatcher(testScheduler)
    next.repository = NewTaskRepository(root, "repo")
    next.refreshSaved()
    runCurrent()
    next.resume(next.saved.value.entries.single())
    runCurrent()
    assertEquals("main", next.baseBranch)
    assertEquals(2, next.chat.value?.thread?.transcript?.value?.size)
    next.say("continue")
    runCurrent()
    assertEquals(sessionId to false, chat.requests.last().let { it.sessionId to it.firstTurn })

    next.discard()
    runCurrent()
    assertFalse(sessionId in chat.saved)
    assertEquals(emptyList(), next.saved.value.entries)
  }

  @Test
  fun `a resumed draft keeps action-only replies and selected action metadata when saved again`() = runTest {
    val suggestions = listOf(
      ChatSuggestedAction("Review", "Review the plan", ChatPermissionMode.ReadOnly, NewTaskActionKind.Review),
      ChatSuggestedAction("Run goal", "Start the goal", ChatPermissionMode.Act, NewTaskActionKind.Goal),
    )
    val selected = ChatSuggestedAction("Inspect", "Check the plan", ChatPermissionMode.Act)
    chat.events = { flowOf(ChatEvent.SuggestedActions(suggestions), ChatEvent.Completed) }
    val first = draft()
    first.say("make a plan")
    runCurrent()
    first.chat.value!!.send(selected.instruction, selected)
    runCurrent()
    val sessionId = chat.requests.first().sessionId
    first.close()
    runCurrent()
    val saved = chat.saved.getValue(sessionId)

    assertEquals(ChatHistoryRole.Agent, saved.lines[1].role)
    assertEquals("", saved.lines[1].text)
    assertEquals(suggestions, saved.lines[1].actions)
    assertEquals(selected, saved.lines[2].selectedAction)

    val next = NewTaskDraft(chat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics())
    next.chatDispatcher = StandardTestDispatcher(testScheduler)
    next.repository = NewTaskRepository(root, "repo")
    next.resume(saved)
    runCurrent()
    val transcript = next.chat.value!!.thread.transcript.value
    assertEquals(suggestions, (transcript[1] as ChatMessage.Agent).actions)
    assertEquals(selected, (transcript[2] as ChatMessage.Operator).action)

    next.close()
    runCurrent()
    assertEquals(saved.lines, chat.saved.getValue(sessionId).lines)
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
    val chats = testNewTaskChats(chat)
    val draft = chats.selected.value.apply {
      repository = NewTaskRepository(root, "repo")
      baseBranch = "main"
      agentId = "claude"
      selectedAgent = this@NewTaskDraftTest.chat.agent
    }
    val workspace = ChatWorkspace(
      chat, FakeWorkDetailRepository(), FakeWorkControlRepository(), testDatabaseSessionController(), WindowCoroutineScope(backgroundScope), Clock.System, ShellPanes(), SelectionHolder(), chats, diagnostics = RecordingDiagnostics(),
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

  @Test
  fun `choosing another repository rebinds the chat to that checkout and keeps the transcript`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val draft = draft()
    draft.say("add export")
    runCurrent()
    val firstSession = chat.requests.single().sessionId
    val otherRoot = createTempDirectory("other-repo").toString()

    draft.repository = NewTaskRepository(otherRoot, "other")
    draft.rebindChat()
    runCurrent()

    val rebound = checkNotNull(draft.chat.value)
    assertNotEquals(firstSession, rebound.sessionId)
    assertEquals(otherRoot, rebound.repositoryRoot)
    assertEquals(listOf(firstSession), chat.ended)
    assertFalse(firstSession in chat.saved)
    assertEquals("add export", (rebound.thread.transcript.value.first() as ChatMessage.Operator).text)
    assertTrue(rebound.thread.transcript.value.last() is ChatMessage.Notice)
    assertEquals(otherRoot, chat.saved.getValue(rebound.sessionId).newTask?.repositoryRoot)

    draft.say("continue")
    runCurrent()
    assertEquals(ChatScope.NewTask(otherRoot, "main"), chat.requests.last().scope)
    assertTrue(chat.requests.last().firstTurn)
    draft.close()
  }

  @Test
  fun `choosing another agent rebinds the chat onto a new session of that agent`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val draft = draft()
    draft.say("add export")
    runCurrent()
    val firstSession = chat.requests.single().sessionId
    val codex = chat.agentFor("codex")

    draft.agentId = "codex"
    draft.selectedAgent = codex
    draft.rebindChat()
    runCurrent()

    val rebound = checkNotNull(draft.chat.value)
    assertNotEquals(firstSession, rebound.sessionId)
    assertEquals("codex", rebound.agent.agentId)
    assertTrue((rebound.thread.transcript.value.last() as ChatMessage.Notice).text.endsWith(REPLAY_NOTICE))
    draft.say("continue")
    runCurrent()
    val first = chat.requests.last()
    assertEquals("codex", first.agent.agentId)
    assertTrue(first.firstTurn)
    assertTrue(first.prompt.startsWith("Earlier in this chat you were talking with claude."))
    assertTrue("Operator: add export\n\nAgent: ok" in first.prompt)
    assertTrue(first.prompt.endsWith("Continue from here.\n\ncontinue"))

    draft.say("and tests")
    runCurrent()
    assertEquals("and tests", chat.requests.last().prompt)
    val operatorTexts = listOf("add export", "continue", "and tests")
    assertEquals(operatorTexts, rebound.thread.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.text })
    assertEquals(operatorTexts, chat.saved.getValue(rebound.sessionId).lines.filter { it.role == ChatHistoryRole.Operator }.map { it.text })
    draft.close()
  }

  @Test
  fun `switching twice without sending replays once and names the agent that ran`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val draft = draft()
    draft.say("add export")
    runCurrent()
    val otherRoot = createTempDirectory("other-repo").toString()

    draft.agentId = "codex"
    draft.selectedAgent = chat.agentFor("codex")
    draft.rebindChat()
    runCurrent()
    draft.agentId = "cursor"
    draft.selectedAgent = chat.agentFor("cursor")
    draft.repository = NewTaskRepository(otherRoot, "other")
    draft.rebindChat()
    runCurrent()
    val rebound = checkNotNull(draft.chat.value)
    assertTrue((rebound.thread.transcript.value.last() as ChatMessage.Notice).text.endsWith(REPLAY_NOTICE))
    draft.say("continue")
    runCurrent()

    val request = chat.requests.last()
    assertEquals("cursor", request.agent.agentId)
    assertEquals(1, request.prompt.split("Earlier in this chat").size - 1)
    assertTrue(request.prompt.startsWith("Earlier in this chat you were talking with claude."))
    assertTrue("The repository changed from $root to $otherRoot." in request.prompt)
    assertTrue(request.prompt.endsWith("\n\ncontinue"))
    draft.close()
  }

  // Clearing the replay on send instead of on the first output would lose it to a failed first turn.
  @Test
  fun `a failed first turn after a switch retries with the earlier conversation`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val draft = draft()
    draft.say("add export")
    runCurrent()
    draft.agentId = "codex"
    draft.selectedAgent = chat.agentFor("codex")
    draft.rebindChat()
    runCurrent()

    chat.events = { flowOf(ChatEvent.Failed(ChatFailure.BinaryMissing, null)) }
    draft.say("continue")
    runCurrent()
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    draft.say("again")
    runCurrent()
    draft.say("more")
    runCurrent()

    val rebound = chat.requests.drop(1)
    assertEquals(listOf(true, true, false), rebound.map { it.firstTurn })
    assertTrue(rebound[0].prompt.startsWith("Earlier in this chat you were talking with claude."))
    assertTrue(rebound[0].prompt.endsWith("\n\ncontinue"))
    assertTrue(rebound[1].prompt.startsWith("Earlier in this chat you were talking with claude."))
    assertTrue(rebound[1].prompt.endsWith("\n\nagain"))
    assertEquals("more", rebound[2].prompt)
    draft.close()
  }

  @Test
  fun `a model change on the same agent keeps the session and replays nothing`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val draft = draft()
    draft.say("add export")
    runCurrent()
    val sessionId = chat.requests.single().sessionId

    draft.selectedAgent = checkNotNull(chat.agent).copy(model = "other")
    draft.rebindChat()
    runCurrent()
    draft.say("continue")
    runCurrent()

    assertEquals(sessionId, draft.chat.value?.sessionId)
    assertEquals(listOf(sessionId), chat.ended)
    assertTrue(checkNotNull(draft.chat.value).thread.transcript.value.none { it is ChatMessage.Notice })
    assertFalse(chat.requests.last().firstTurn)
    assertEquals("continue", chat.requests.last().prompt)
    draft.close()
  }

  @Test
  fun `rebind is a no-op when the repository and agent did not change`() = runTest {
    chat.events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) }
    val draft = draft()
    draft.say("add export")
    runCurrent()
    val sessionId = chat.requests.single().sessionId

    draft.rebindChat()
    runCurrent()

    assertEquals(sessionId, draft.chat.value?.sessionId)
    assertTrue(chat.ended.isEmpty())
    draft.close()
  }

  @Test
  fun `messages queued during a reply send as one follow-up without splitting the streaming answer`() = runTest {
    val replies = mutableListOf<Channel<ChatEvent>>()
    chat.events = {
      val reply = Channel<ChatEvent>(Channel.UNLIMITED)
      replies += reply
      flow { for (event in reply) emit(event) }
    }
    val draft = draft()
    draft.say("first")
    runCurrent()
    replies[0].send(ChatEvent.TextDelta("Before "))
    runCurrent()
    draft.say("second")
    draft.say("third")
    draft.say("fourth")
    runCurrent()

    val thread = checkNotNull(draft.chat.value).thread
    assertEquals(1, chat.requests.size)
    assertEquals(3, thread.queued.value.size)
    replies[0].send(ChatEvent.TextDelta("after"))
    replies[0].send(ChatEvent.Completed)
    replies[0].close()
    runCurrent()

    assertEquals(2, chat.requests.size)
    assertEquals("first", (thread.transcript.value.filterIsInstance<ChatMessage.Operator>()[0]).text)
    val combined = "second\n\nthird\n\nfourth"
    assertEquals(combined, chat.requests[1].prompt)
    assertEquals(combined, (thread.transcript.value.filterIsInstance<ChatMessage.Operator>()[1]).text)
    assertEquals("Before after", thread.transcript.value.filterIsInstance<ChatMessage.Agent>().first().text)
    assertTrue(thread.queued.value.isEmpty())
    draft.say("later")
    runCurrent()
    assertEquals(2, chat.requests.size)
    replies[1].send(ChatEvent.Completed)
    replies[1].close()
    runCurrent()

    assertEquals(3, chat.requests.size)
    assertEquals("later", chat.requests[2].prompt)
    assertEquals("later", thread.transcript.value.filterIsInstance<ChatMessage.Operator>().last().text)
    assertTrue(thread.queued.value.isEmpty())
    draft.close()
  }

  @Test
  fun `an unprompted reply holds queued messages until it ends`() = runTest {
    val draft = draft()
    draft.say("start")
    runCurrent()
    val thread = checkNotNull(draft.chat.value).thread
    val sessionId = chat.requests.single().sessionId
    chat.unpromptedEvents.emit(sessionId to ChatEvent.TextDelta("done"))
    runCurrent()
    draft.say("next")
    runCurrent()
    assertEquals(1, chat.requests.size)
    assertEquals(1, thread.queued.value.size)

    chat.unpromptedEvents.emit(sessionId to ChatEvent.Completed)
    runCurrent()
    assertEquals(2, chat.requests.size)
    assertTrue(thread.queued.value.isEmpty())
    draft.close()
  }

  @Test
  fun `discard drops queued messages so they never send`() = runTest {
    chat.events = { flow { awaitCancellation() } }
    val draft = draft()
    draft.say("one")
    runCurrent()
    draft.say("must not run")
    runCurrent()
    assertEquals(1, checkNotNull(draft.chat.value).thread.queued.value.size)

    draft.discard()
    runCurrent()
    assertNull(draft.chat.value)
    assertEquals(1, chat.requests.size)
  }

  @Test
  fun `stop with a cancelling save still saves the partial reply and drains the queue`() = runTest {
    val cancelling = object : ExecutionChatRepository by chat {
      override suspend fun saveNewTaskDraft(entry: ChatHistoryEntry): Boolean {
        coroutineContext.ensureActive()
        return chat.saveNewTaskDraft(entry)
      }
    }
    val replies = mutableListOf<Channel<ChatEvent>>()
    chat.events = {
      val reply = Channel<ChatEvent>(Channel.UNLIMITED)
      replies += reply
      flow { for (event in reply) emit(event) }
    }
    val draft = draft(cancelling)
    draft.say("first")
    runCurrent()
    val sessionId = chat.requests.single().sessionId
    replies[0].send(ChatEvent.TextDelta("part"))
    runCurrent()
    draft.say("queued")
    runCurrent()
    draft.stop()
    runCurrent()
    val transcript = checkNotNull(draft.chat.value).thread.transcript.value
    assertEquals("part", transcript.filterIsInstance<ChatMessage.Agent>().single().text)
    assertEquals(ChatMessage.Notice(STOPPED_NOTICE), transcript.last())
    assertFalse(draft.chat.value!!.thread.busy.value)
    assertTrue(sessionId in chat.saved)
    chat.events = { flowOf(ChatEvent.Completed) }
    draft.say("next")
    runCurrent()
    assertEquals(sessionId, chat.requests.last().sessionId)
    assertFalse(chat.requests.last().firstTurn)
    draft.close()
  }

  @Test
  fun `stop right after send clears busy when the turn body never ran`() = runTest {
    chat.events = { flow { awaitCancellation() } }
    val draft = draft()
    draft.say("one")
    draft.stop()
    runCurrent()
    assertFalse(checkNotNull(draft.chat.value).thread.busy.value)
    chat.events = { flowOf(ChatEvent.Completed) }
    draft.say("two")
    runCurrent()
    assertEquals("two", chat.requests.last().prompt)
    draft.close()
  }

  @Test
  fun `stop before output rotates the session for the next turn`() = runTest {
    chat.events = { flow { awaitCancellation() } }
    val draft = draft()
    draft.say("one")
    runCurrent()
    val oldId = chat.requests.single().sessionId
    draft.stop()
    runCurrent()
    assertFalse(oldId in chat.saved)
    val newId = checkNotNull(draft.chat.value).sessionId
    assertNotEquals(oldId, newId)
    assertEquals(newId, draft.chat.value!!.sessionIdFlow.value)
    assertFalse(chat.saved.getValue(newId).newTask!!.sessionEstablished)
    chat.events = { flowOf(ChatEvent.Completed) }
    draft.say("two")
    runCurrent()
    assertEquals(newId, chat.requests.last().sessionId)
    assertEquals(newId, draft.chat.value!!.sessionIdFlow.value)
    assertTrue(chat.requests.last().firstTurn)
    draft.close()
  }

  @Test
  fun `stop on an unprompted reply returns the queue and ignores a late event`() = runTest {
    chat.events = { flowOf(ChatEvent.Completed) }
    val draft = draft()
    draft.say("start")
    runCurrent()
    val sessionId = chat.requests.single().sessionId
    chat.unpromptedEvents.emit(sessionId to ChatEvent.TextDelta("bg"))
    runCurrent()
    draft.say("queued")
    runCurrent()
    draft.text = "held"
    assertEquals(1, chat.requests.size)
    draft.stop()
    runCurrent()
    assertEquals(listOf(sessionId), chat.ended)
    assertEquals(1, chat.requests.size)
    assertEquals("queued\n\nheld", draft.text)
    val beforeLate = checkNotNull(draft.chat.value).thread.transcript.value.size
    chat.unpromptedEvents.emit(sessionId to ChatEvent.TextDelta("late"))
    runCurrent()
    assertFalse(draft.chat.value!!.thread.busy.value)
    assertEquals(beforeLate, draft.chat.value!!.thread.transcript.value.size)
    draft.close()
  }

  @Test
  fun `stop with a queue restores queued text before existing draft text`() = runTest {
    val replies = mutableListOf<Channel<ChatEvent>>()
    chat.events = {
      val reply = Channel<ChatEvent>(Channel.UNLIMITED)
      replies += reply
      flow { for (event in reply) emit(event) }
    }
    val draft = draft()
    draft.say("first")
    runCurrent()
    draft.cursorMcpDependency = CursorMcpDependency.ProjectUserOnly
    draft.say("q1")
    draft.say("q2")
    runCurrent()
    draft.text = "existing"
    draft.stop()
    runCurrent()
    assertEquals(1, chat.requests.size)
    assertEquals("q1\n\nq2\n\nexisting", draft.text)
    assertEquals(CursorMcpDependency.ProjectUserOnly, draft.cursorMcpDependency)
    replies[0].close()
    runCurrent()
    draft.close()
  }

  @Test
  fun `send now stops the running reply and sends queued messages as one follow-up`() = runTest {
    val replies = mutableListOf<Channel<ChatEvent>>()
    chat.events = {
      val reply = Channel<ChatEvent>(Channel.UNLIMITED)
      replies += reply
      flow { for (event in reply) emit(event) }
    }
    val draft = draft()
    draft.say("first")
    runCurrent()
    draft.say("second")
    draft.say("third")
    runCurrent()
    draft.sendNow()
    runCurrent()
    replies[0].close()
    runCurrent()
    replies[1].send(ChatEvent.Completed)
    replies[1].close()
    runCurrent()
    val transcript = checkNotNull(draft.chat.value).thread.transcript.value
    val stoppedAt = transcript.indexOfFirst { it is ChatMessage.Notice && it.text == STOPPED_NOTICE }
    val secondOperator = transcript.indexOfFirst { it is ChatMessage.Operator && it.text == "second\n\nthird" }
    assertTrue(stoppedAt >= 0 && secondOperator > stoppedAt)
    assertEquals(listOf("first", "second\n\nthird"), chat.requests.map { it.prompt })
    assertTrue(checkNotNull(draft.chat.value).thread.queued.value.isEmpty())
    draft.close()
  }

  @Test
  fun `send now during an unprompted reply drops a late event and stops before the queued turn`() = runTest {
    chat.events = { flowOf(ChatEvent.Completed) }
    val draft = draft()
    draft.say("start")
    runCurrent()
    val sessionId = chat.requests.single().sessionId
    chat.unpromptedEvents.emit(sessionId to ChatEvent.TextDelta("bg"))
    runCurrent()
    draft.say("queued")
    runCurrent()
    draft.sendNow()
    chat.unpromptedEvents.emit(sessionId to ChatEvent.TextDelta("late"))
    runCurrent()
    val transcript = checkNotNull(draft.chat.value).thread.transcript.value
    val stoppedAt = transcript.indexOfFirst { it is ChatMessage.Notice && it.text == STOPPED_NOTICE }
    val queuedOperator = transcript.indexOfFirst { it is ChatMessage.Operator && it.text == "queued" }
    assertTrue(stoppedAt >= 0 && queuedOperator > stoppedAt)
    assertFalse(transcript.filterIsInstance<ChatMessage.Agent>().any { it.text == "late" })
    assertFalse(checkNotNull(draft.chat.value).thread.busy.value)
    draft.close()
  }
}
