package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class NewTaskComposerTest {
  @Test
  fun `opening focuses the input and clearing focus releases the single-key shortcuts`() = runComposeUiTest {
    val input = ShellInput()
    lateinit var focusManager: FocusManager
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides input) {
          focusManager = LocalFocusManager.current
          NewTaskComposer(NewTaskUiState(), testNewTaskDraft()) {}
        }
      }
    }

    onNode(hasSetTextAction()).assertIsFocused()
    assertTrue(input.textInputFocused)

    runOnIdle { focusManager.clearFocus(force = true) }
    waitForIdle()
    assertFalse(input.textInputFocused)
  }

  @Test
  fun `the chat shows the same activity states as a run chat and the home screen shows none`() = runComposeUiTest {
    val chat = FakeExecutionChatRepository()
    val events = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { events.consumeAsFlow().takeWhile { it != ChatEvent.Completed } }
    val draft = NewTaskDraft(
      chat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
    ).apply {
      chatDispatcher = Dispatchers.Unconfined
      repository = NewTaskRepository(
        createTempDirectory("new-task-repo").also { it.toFile().deleteOnExit() }.toString(),
        "repo",
      )
      agentId = "claude"
      selectedAgent = chat.agent
      text = "Add a button"
    }
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(NewTaskUiState(), draft) {}
        }
      }
    }
    fun send(event: ChatEvent) {
      events.trySend(event)
      waitForIdle()
    }

    onNodeWithText("Ready").assertDoesNotExist()

    runOnIdle { draft.send() }
    waitForIdle()
    onNodeWithText("Thinking...").assertExists()

    val read = ChatToolCall("read", "Read", "View.kt", "Read the view")
    send(ChatEvent.ToolUse(read))
    onNodeWithText("Reading files...").assertExists()

    send(ChatEvent.ToolResult(read.id, "contents", false))
    send(ChatEvent.TextDelta("Done."))
    onNodeWithText("Responding...").assertExists()

    send(ChatEvent.Completed)
    waitUntil { draft.chat.value?.thread?.busy?.value == false }
    onNodeWithText("Ready").assertExists()
  }

  @Test
  fun `repository and agent chips stay clickable after the first reply`() = runComposeUiTest {
    val chat = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val events = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { events.consumeAsFlow().takeWhile { it != ChatEvent.Completed } }
    val repository = NewTaskRepository(
      createTempDirectory("new-task-repo").also { it.toFile().deleteOnExit() }.toString(),
      "repo",
    )
    val draft = NewTaskDraft(
      chat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
    ).apply {
      chatDispatcher = Dispatchers.Unconfined
      this.repository = repository
      agentId = "claude"
      selectedAgent = chat.agent
      text = "Add a button"
    }
    val actions = mutableListOf<NewTaskAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(
            NewTaskUiState(repositories = listOf(repository), agents = listOf("claude", "codex"), agent = chat.agent),
            draft,
          ) { actions += it }
        }
      }
    }

    runOnIdle { draft.send() }
    waitForIdle()
    onNode(hasClickLabel("Change repository or base branch")).assertIsNotEnabled()
    onNode(hasClickLabel("Change agent")).assertIsNotEnabled()

    events.trySend(ChatEvent.Completed)
    waitUntil { draft.chat.value?.thread?.busy?.value == false }
    waitForIdle()

    onNode(hasClickLabel("Change repository or base branch")).assertIsEnabled().performClick()
    assertTrue(actions.contains(NewTaskAction.OpenRepositoryPicker))
    onNode(hasClickLabel("Change agent")).assertIsEnabled().performClick()
    onNodeWithText("codex").assertExists()
  }

  @Test
  fun `a follow-up during a reply queues until the turn ends`() = runComposeUiTest {
    val chat = FakeExecutionChatRepository()
    val events = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { events.consumeAsFlow().takeWhile { it != ChatEvent.Completed } }
    val repository = NewTaskRepository(
      createTempDirectory("new-task-repo").also { it.toFile().deleteOnExit() }.toString(),
      "repo",
    )
    val draft = NewTaskDraft(
      chat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
    ).apply {
      chatDispatcher = Dispatchers.Unconfined
      this.repository = repository
      agentId = "claude"
      selectedAgent = chat.agent
      text = "Add a button"
    }
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(NewTaskUiState(agent = chat.agent, agents = listOf("claude")), draft) { action ->
            when (action) {
              NewTaskAction.Send -> draft.send()
              is NewTaskAction.TextChanged -> draft.text = action.text
              else -> Unit
            }
          }
        }
      }
    }

    runOnIdle { draft.send() }
    waitForIdle()
    onNodeWithText("Queue").assertExists()
    runOnIdle { draft.text = "and a label" }
    waitForIdle()
    onNodeWithText("Queue").performClick()
    onNodeWithText("1 message queued").assertExists()
    runOnIdle {
      assertEquals(listOf("and a label"), draft.chat.value?.thread?.queued?.value?.map { it.text })
      assertEquals("", draft.text)
    }

    events.trySend(ChatEvent.Completed)
    waitUntil { draft.chat.value?.thread?.busy?.value == false && draft.chat.value?.thread?.queued?.value?.isEmpty() == true }
    waitForIdle()
    onNodeWithText("Send").assertExists()
    onNodeWithText("1 message queued").assertDoesNotExist()
  }

  @Test
  fun `disabled repository and agent chips expose why and stay off while busy starting or missing repository`() = runTest {
    val repository = NewTaskRepository(
      createTempDirectory("new-task-repo").also { it.toFile().deleteOnExit() }.toString(),
      "repo",
    )
    val missingChat = FakeExecutionChatRepository().apply { events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) } }
    val missingRoot = createTempDirectory("missing-repo").also { it.toFile().deleteOnExit() }.toString()
    val missingRepo = NewTaskRepository(missingRoot, "repo")
    val seedDraft = NewTaskDraft(
      missingChat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
    ).apply {
      chatDispatcher = Dispatchers.Unconfined
      this.repository = missingRepo
      agentId = "claude"
      selectedAgent = missingChat.agent
      text = "Add export"
    }
    seedDraft.send()
    val saved = checkNotNull(missingChat.saved.values.single())
    val entry = saved.copy(
      newTask = checkNotNull(saved.newTask).copy(repositoryRoot = "$missingRoot/removed"),
    )
    seedDraft.close()
    val missingDraft = NewTaskDraft(
      missingChat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
    ).apply {
      chatDispatcher = Dispatchers.Unconfined
      this.repository = missingRepo
      agentId = "claude"
      selectedAgent = missingChat.agent
    }
    missingDraft.resume(entry)

    runComposeUiTest {
      fun compose(draft: NewTaskDraft) {
        setContent {
          SkillBillTheme(reducedMotion = true) {
            CompositionLocalProvider(LocalShellInput provides ShellInput()) {
              NewTaskComposer(NewTaskUiState(repositories = listOf(repository), agents = listOf("claude"), agent = draft.selectedAgent), draft) {}
            }
          }
        }
      }
      fun assertChipsDisabled(why: String) {
        onNode(hasClickLabel("Change repository or base branch")).assertIsNotEnabled()
          .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, why))
        onNode(hasClickLabel("Change agent")).assertIsNotEnabled()
          .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, why))
      }

      val busyChat = FakeExecutionChatRepository()
      val busyEvents = Channel<ChatEvent>(Channel.UNLIMITED)
      busyChat.events = { busyEvents.consumeAsFlow().takeWhile { it != ChatEvent.Completed } }
      val busyDraft = NewTaskDraft(
        busyChat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
        WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
      ).apply {
        chatDispatcher = Dispatchers.Unconfined
        this.repository = repository
        agentId = "claude"
        selectedAgent = busyChat.agent
        text = "Add a button"
      }
      compose(busyDraft)
      runOnIdle { busyDraft.send() }
      waitForIdle()
      assertChipsDisabled("Wait for the current reply to finish")
      busyEvents.trySend(ChatEvent.Completed)
      waitForIdle()

      val startChat = FakeExecutionChatRepository().apply { events = { flowOf(ChatEvent.Completed) } }
      val startControls = FakeWorkControlRepository()
      val startGate = CompletableDeferred<NewTaskStartOutcome>()
      val hangingControls = object : WorkControlRepository by startControls {
        override suspend fun startNewTask(request: NewTaskStart): NewTaskStartOutcome {
          startControls.newTaskRequests += request
          return startGate.await()
        }
      }
      val startDraft = NewTaskDraft(
        startChat, hangingControls, testDatabaseSessionController(), ToastQueue(),
        WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
      ).apply {
        chatDispatcher = Dispatchers.Unconfined
        this.repository = repository
        agentId = "claude"
        selectedAgent = startChat.agent
        text = "Add a button"
      }
      compose(startDraft)
      runOnIdle { startDraft.send() }
      waitForIdle()
      runOnIdle { startDraft.start(NewTaskKind.GoalRun) }
      waitForIdle()
      try {
        assertChipsDisabled("Wait for the start to finish")
      } finally {
        startGate.complete(NewTaskStartOutcome.Refused("done"))
        waitForIdle()
      }

      compose(missingDraft)
      waitForIdle()
      assertChipsDisabled("This draft's repository is missing")
    }
  }

  @Test
  fun `stop and send now follow stoppable state and the send now gate`() = runComposeUiTest {
    val chat = FakeExecutionChatRepository()
    val events = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { events.consumeAsFlow().takeWhile { it != ChatEvent.Completed } }
    val repository = NewTaskRepository(
      createTempDirectory("new-task-repo").also { it.toFile().deleteOnExit() }.toString(),
      "repo",
    )
    val draft = NewTaskDraft(
      chat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
    ).apply {
      chatDispatcher = Dispatchers.Unconfined
      this.repository = repository
      agentId = "claude"
      selectedAgent = chat.agent
      text = "Add a button"
    }
    var instant = Instant.fromEpochMilliseconds(0)
    val actions = mutableListOf<NewTaskAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(NewTaskUiState(agent = chat.agent, agents = listOf("claude")), draft, now = { instant }) { action ->
            actions += action
            if (action == NewTaskAction.Send) draft.send()
          }
        }
      }
    }
    onNodeWithText("Stop", substring = false).assertDoesNotExist()
    runOnIdle { draft.send() }
    waitForIdle()
    onNodeWithText("Stop", substring = false).assertExists()
    onNodeWithText("Queue", substring = false).assertExists()
    runOnIdle { draft.text = "follow-up" }
    waitForIdle()
    onNodeWithText("Queue", substring = false).performClick()
    waitForIdle()
    onNodeWithText("Send now", substring = false).assertExists()
    instant = instant + 100.milliseconds
    onNodeWithText("Send now", substring = false).performClick()
    assertFalse(actions.contains(NewTaskAction.SendNow))
    instant = instant + 700.milliseconds
    onNodeWithText("Send now", substring = false).performClick()
    assertEquals(listOf(NewTaskAction.SendNow), actions.filter { it == NewTaskAction.SendNow })
    instant = instant + 700.milliseconds
    onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
    assertEquals(2, actions.count { it == NewTaskAction.SendNow })
  }

  private fun hasClickLabel(label: String) = SemanticsMatcher("click $label") {
    it.config.getOrNull(SemanticsActions.OnClick)?.label == label
  }
}
