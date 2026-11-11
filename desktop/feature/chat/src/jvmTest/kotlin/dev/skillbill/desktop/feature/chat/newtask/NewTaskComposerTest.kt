package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import kotlin.time.Duration.Companion.minutes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.RepositoryBranch
import dev.skillbill.desktop.core.domain.model.RepositoryBranches
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
  fun `the open picker replaces the base branch input with a filter`() = runComposeUiTest {
    val draft = testNewTaskDraft().apply { repository = NewTaskRepository("/work/app", "app") }
    val read = RepositoryBranches("/work/app", "main", emptyList(), emptyList())
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(
            NewTaskUiState(repositoryPickerOpen = true, branchRead = BranchReadState.Loaded("/work/app", read)),
            draft,
          ) {}
        }
      }
    }
    waitForIdle()

    onNodeWithText("Base branch").assertExists()
    onAllNodes(hasSetTextAction()).assertCountEquals(2)
  }

  @Test
  fun `the default row reflects the restored branch without changing the draft`() = runComposeUiTest {
    val repository = NewTaskRepository("/work/app", "app")
    val draft = testNewTaskDraft().apply {
      this.repository = repository
      baseBranch = "main"
    }
    val branches = RepositoryBranches(
      repository.root,
      "main",
      listOf(RepositoryBranch("feature/local", Instant.fromEpochMilliseconds(1))),
      listOf(RepositoryBranch("origin/feature/remote", Instant.fromEpochMilliseconds(3))),
    )
    var state by mutableStateOf(NewTaskUiState(
      repositories = listOf(repository),
      repositoryPickerOpen = true,
      branchRead = BranchReadState.Loaded(repository.root, branches),
    ))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(state, draft) {}
        }
      }
    }
    waitForIdle()

    onNodeWithContentDescription("main, default", useUnmergedTree = true).assertIsSelected()
    assertEquals("main", draft.baseBranch)
    onNodeWithContentDescription("feature/local", substring = true, useUnmergedTree = true).assert(notSelected())
    onNodeWithContentDescription("origin/feature/remote", substring = true, useUnmergedTree = true).assert(notSelected())

    onNodeWithContentDescription("Filter branches").performTextInput("mai")
    waitForIdle()
    onNodeWithContentDescription("main, default", useUnmergedTree = true).assertIsSelected()
    assertEquals("main", draft.baseBranch)

    onNodeWithContentDescription("Filter branches").performTextReplacement("")
    waitForIdle()
    runOnIdle { draft.baseBranch = "feature/local" }
    waitForIdle()
    onNodeWithContentDescription("main, default", useUnmergedTree = true).assert(notSelected())
    onNodeWithContentDescription("feature/local", substring = true, useUnmergedTree = true).assertIsSelected()
    assertEquals("feature/local", draft.baseBranch)

    runOnIdle { draft.baseBranch = "origin/feature/remote" }
    waitForIdle()
    onNodeWithContentDescription("origin/feature/remote", substring = true, useUnmergedTree = true).assertIsSelected()
    assertEquals("origin/feature/remote", draft.baseBranch)

    runOnIdle { draft.baseBranch = "" }
    waitForIdle()
    onNodeWithContentDescription("main, default", useUnmergedTree = true).assertIsSelected()
    assertEquals("", draft.baseBranch)

    runOnIdle { draft.baseBranch = "main" }
    waitForIdle()
    runOnIdle {
      draft.repository = NewTaskRepository("/work/other", "other")
      state = state.copy(repositories = state.repositories + draft.repository!!)
    }
    waitForIdle()
    onNodeWithContentDescription("default branch, default", useUnmergedTree = true).assert(notSelected())
    assertEquals("main", draft.baseBranch)

    runOnIdle { state = state.copy(branchRead = BranchReadState.Failed("/work/other", "unavailable")) }
    waitForIdle()
    onNodeWithContentDescription("default branch, default", useUnmergedTree = true).assert(notSelected())
    assertEquals("main", draft.baseBranch)

    runOnIdle { draft.baseBranch = "" }
    waitForIdle()
    onNodeWithContentDescription("default branch, default", useUnmergedTree = true).assertIsSelected()
    assertEquals("", draft.baseBranch)
  }

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
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
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
    onNodeWithText("Reading View.kt").assertExists()

    send(ChatEvent.ToolResult(read.id, "contents", false))
    send(ChatEvent.TextDelta("Done."))
    onNodeWithText("Working...").assertExists()

    send(ChatEvent.Completed)
    waitUntil { draft.chat.value?.thread?.busy?.value == false }
    onNodeWithText("Ready").assertExists()
  }

  @Test
  fun `the repository pill stays available after a chat reply`() = runComposeUiTest {
    val chat = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val events = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { events.consumeAsFlow().takeWhile { it != ChatEvent.Completed } }
    val repository = NewTaskRepository(
      createTempDirectory("new-task-repo").also { it.toFile().deleteOnExit() }.toString(),
      "repo",
    )
    val draft = NewTaskDraft(
      chat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
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

    onNode(hasClickLabel("Change repository or base branch")).assertIsEnabled().performClick()
    assertTrue(actions.contains(NewTaskAction.OpenRepositoryPicker))

    runOnIdle { draft.send() }
    waitForIdle()
    onNode(hasClickLabel("Change repository or base branch")).assertDoesNotExist()
    onNodeWithContentDescription(REPOSITORY_PILL_CHEVRON, useUnmergedTree = true).assertDoesNotExist()
    onNode(hasClickLabel("Change agent or model")).assertIsNotEnabled()

    events.trySend(ChatEvent.Completed)
    waitUntil { draft.chat.value?.thread?.busy?.value == false }
    waitForIdle()

    onNode(hasClickLabel("Change repository or base branch")).assertIsEnabled()
    onNodeWithContentDescription(REPOSITORY_PILL_CHEVRON, useUnmergedTree = true).assertExists()
    onNode(hasClickLabel("Change repository or base branch")).performClick()
    assertTrue(actions.contains(NewTaskAction.OpenRepositoryPicker))
    onNode(hasClickLabel("Change agent or model")).assertIsEnabled().performClick()
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
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
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
  fun `the repository pill is static with a chat and disabled while starting and the agent chip stays off`() = runTest {
    val repository = NewTaskRepository(
      createTempDirectory("new-task-repo").also { it.toFile().deleteOnExit() }.toString(),
      "repo",
    )
    val missingChat = FakeExecutionChatRepository().apply { events = { flowOf(ChatEvent.TextDelta("ok"), ChatEvent.Completed) } }
    val missingRoot = createTempDirectory("missing-repo").also { it.toFile().deleteOnExit() }.toString()
    val missingRepo = NewTaskRepository(missingRoot, "repo")
    val seedDraft = NewTaskDraft(
      missingChat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
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
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
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
      val busyChat = FakeExecutionChatRepository()
      val busyEvents = Channel<ChatEvent>(Channel.UNLIMITED)
      busyChat.events = { busyEvents.consumeAsFlow().takeWhile { it != ChatEvent.Completed } }
      val busyDraft = NewTaskDraft(
        busyChat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
        WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
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
      onNode(hasClickLabel("Change repository or base branch")).assertDoesNotExist()
      onNode(hasClickLabel("Change agent or model")).assertIsNotEnabled()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Wait for the current reply to finish"))
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
        WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
      ).apply {
        chatDispatcher = Dispatchers.Unconfined
        this.repository = repository
        agentId = "claude"
        selectedAgent = startChat.agent
        text = "Add a button"
      }
      compose(startDraft)
      runOnIdle { startDraft.start(NewTaskKind.Review) }
      waitForIdle()
      try {
        val startingWhy = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Wait for the start to finish")
        onNode(hasClickLabel("Change repository or base branch")).assertIsNotEnabled().assert(startingWhy)
        onNode(hasClickLabel("Change agent or model")).assertIsNotEnabled().assert(startingWhy)
      } finally {
        startGate.complete(NewTaskStartOutcome.Refused("done"))
        waitForIdle()
      }

      compose(missingDraft)
      waitForIdle()
      onNode(hasClickLabel("Change repository or base branch")).assertDoesNotExist()
      val missingWhy = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "This draft's repository is missing")
      onAllNodes(missingWhy).assertCountEquals(2)
      onNode(missingWhy and hasText("removed")).assertExists()
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
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
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

  @Test
  fun `a long branch shrinks before the repository name and the chevron stays`() = runComposeUiTest {
    val draft = testNewTaskDraft().apply {
      repository = NewTaskRepository("/work/repository", "repository")
      baseBranch = "short"
    }
    var read by mutableStateOf(RepositoryBranches("/work/repository", "main", emptyList(), emptyList(), currentBranch = "short"))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(NewTaskUiState(branchRead = BranchReadState.Loaded(read.repositoryRoot, read)), draft) {}
        }
      }
    }
    fun repositoryWidth() = onNodeWithText("repository", useUnmergedTree = true).fetchSemanticsNode().size.width
    val shortBranchWidth = repositoryWidth()

    runOnIdle { read = read.copy(currentBranch = "b".repeat(200)) }
    waitForIdle()

    assertEquals(shortBranchWidth, repositoryWidth())
    assertTrue(onNodeWithContentDescription(REPOSITORY_PILL_CHEVRON, useUnmergedTree = true).fetchSemanticsNode().size.width > 0)
  }

  @Test
  fun `with no repository the pill offers the choice and opens the picker`() = runComposeUiTest {
    val draft = testNewTaskDraft()
    var state by mutableStateOf(NewTaskUiState())
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(state, draft) { if (it == NewTaskAction.OpenRepositoryPicker) state = state.copy(repositoryPickerOpen = true) }
        }
      }
    }

    onNodeWithText("Choose repository", useUnmergedTree = true).assertExists()
    onNodeWithText(" / ", useUnmergedTree = true).assertDoesNotExist()
    onNode(hasClickLabel("Change repository or base branch")).assertIsEnabled().performClick()
    waitForIdle()

    onNodeWithText("Add repository", useUnmergedTree = true).assertExists()
    onNodeWithText("Repository path", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun `the repository filter appears only above seven rows`() = runComposeUiTest {
    var state by mutableStateOf(openPicker(7))
    setPicker { state }

    onNodeWithText("Filter repositories", useUnmergedTree = true).assertDoesNotExist()

    runOnIdle { state = openPicker(8) }
    waitForIdle()
    onNodeWithText("Filter repositories", useUnmergedTree = true).assertExists()
  }

  @Test
  fun `the repository filter ignores case and outer spaces and reports no match`() = runComposeUiTest {
    val state = openPicker(8)
    setPicker { state }

    onNodeWithContentDescription("Filter repositories").performTextInput(" REPO1 ")
    waitForIdle()
    onNodeWithText("repo1", useUnmergedTree = true).assertExists()
    onNodeWithText("repo0", useUnmergedTree = true).assertDoesNotExist()

    onNodeWithContentDescription("Filter repositories").performTextReplacement("zzz")
    waitForIdle()
    onNodeWithText("No repository matches \"zzz\"", useUnmergedTree = true).assertExists()
  }

  @Test
  fun `two repositories with one name show their parent folders`() = runComposeUiTest {
    val state = NewTaskUiState(
      repositories = listOf(NewTaskRepository("/work/a/app", "app"), NewTaskRepository("/other/b/app", "app"), NewTaskRepository("/work/c/tool", "tool")),
      repositoryPickerOpen = true,
    )
    setPicker { state }

    onNodeWithText("a", useUnmergedTree = true).assertExists()
    onNodeWithText("b", useUnmergedTree = true).assertExists()
    onNodeWithText("c", useUnmergedTree = true).assertDoesNotExist()
    onNodeWithContentDescription("app, a").assertExists()
    onNodeWithContentDescription("app, b").assertExists()
  }

  private fun openPicker(rows: Int) = NewTaskUiState(
    repositories = List(rows) { NewTaskRepository("/work/repo$it", "repo$it") },
    repositoryPickerOpen = true,
  )

  private fun ComposeUiTest.setPicker(state: () -> NewTaskUiState) {
    val draft = testNewTaskDraft()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(state(), draft) {}
        }
      }
    }
    waitForIdle()
  }

  private fun notSelected() = SemanticsMatcher("not selected") {
    it.config.getOrNull(SemanticsProperties.Selected) != true
  }

  private fun hasClickLabel(label: String) = SemanticsMatcher("click $label") {
    it.config.getOrNull(SemanticsActions.OnClick)?.label == label
  }

  // Esc inside the palette's dialog must still tell the composer to close, and focus must come back to the task input.
  @Test
  fun `Esc closes the resume palette, sends DismissResume, and refocuses the composer`() = runComposeUiTest {
    var state by mutableStateOf(NewTaskUiState(resumePickerOpen = true))
    val actions = mutableListOf<NewTaskAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(state, testNewTaskDraft()) { action ->
            actions += action
            if (action == NewTaskAction.DismissResume) state = state.copy(resumePickerOpen = false)
          }
        }
      }
    }
    onNodeWithText("No saved chats yet").assertExists()
    onNodeWithText("Pick a chat to see its latest messages.").assertExists()
    val filter = onNode(hasSetTextAction() and hasText("Filter by title, branch or agent"))
    filter.assertIsFocused()
    filter.performKeyInput { pressKey(Key.Escape) }
    waitForIdle()
    assertTrue(NewTaskAction.DismissResume in actions)
    onNodeWithText("No saved chats yet").assertDoesNotExist()
    onNode(hasSetTextAction()).assertIsFocused()
  }

  @Test
  fun `the resume palette lists only the picked repository, lifts the cap, and resumes the third draft on Enter`() = runComposeUiTest {
    val now = Instant.parse("2026-11-06T06:00:00Z")
    val chats = FakeExecutionChatRepository()
    fun saved(id: String, root: String, minutesAgo: Int) = ChatHistoryEntry(
      id, null, ChatAgent("claude", null, null), now - minutesAgo.minutes, "$id chat",
      listOf(ChatHistoryLine(ChatHistoryRole.Operator, "$id chat")), ChatNewTaskContext(root, "main"),
    )
    val app = (1..42).map { saved("app-$it", "/work/app", it) }
    val web = (1..5).map { saved("web-$it", "/work/web", it) }
    (app + web).forEach { chats.saved[it.sessionId] = it }
    val draft = NewTaskDraft(
      chats, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System, diagnostics = RecordingDiagnostics(),
    ).apply { repository = NewTaskRepository("/work/app", "app") }
    draft.refreshSaved()
    var state by mutableStateOf(NewTaskUiState())
    val actions = mutableListOf<NewTaskAction>()
    lateinit var focusManager: FocusManager
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          focusManager = LocalFocusManager.current
          NewTaskComposer(state, draft, now = { now }) { action ->
            actions += action
            if (action is NewTaskAction.ResumeDraft || action == NewTaskAction.DismissResume) state = state.copy(resumePickerOpen = false)
          }
        }
      }
    }
    runOnIdle { focusManager.clearFocus(force = true) }
    onNode(hasSetTextAction()).assertIsNotFocused()
    state = state.copy(resumePickerOpen = true)
    fun row(id: String) = hasText("$id chat") and hasText("main · claude")
    val list = onNode(hasScrollToIndexAction())
    onNodeWithText("30 of 42 chats").assertExists()
    onNodeWithText("Today").assertExists()
    onNode(row("app-1")).assertExists()
    onAllNodes(hasText("web-", substring = true)).assertCountEquals(0)
    list.performScrollToNode(hasText("Show all 42 chats"))
    onNodeWithText("Show all 42 chats").performClick()
    onNodeWithText("42 chats").assertExists()
    list.performScrollToNode(row("app-42"))
    onAllNodes(hasText("web-", substring = true)).assertCountEquals(0)
    val filter = onNode(hasSetTextAction() and hasText("Filter by title, branch or agent"))
    filter.performKeyInput {
      pressKey(Key.DirectionDown)
      pressKey(Key.DirectionDown)
      pressKey(Key.Enter)
    }
    waitForIdle()
    assertEquals(listOf<NewTaskAction>(NewTaskAction.ResumeDraft(app[2])), actions)
    onNodeWithText("42 chats").assertDoesNotExist()
    onNode(hasSetTextAction()).assertIsFocused()
  }
}
