package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.ChatModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.DetectedAgent
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RepositoryBranches
import dev.skillbill.desktop.core.domain.model.RepositoryBranchesRead
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecDeleteOutcome
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.FolderChoice
import dev.skillbill.desktop.core.domain.repository.FolderChooser
import dev.skillbill.desktop.core.domain.repository.RepositoryBranchesRepository
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.NewTaskChatLayout
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.ChatWorkspace
import dev.skillbill.desktop.feature.chat.chipText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskViewModelTest {
  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(board: FakeWorkBoardRepository, chats: NewTaskChats, specs: SpecRepository = FakeSpecs(), branches: RepositoryBranchesRepository = FakeBranches(), folders: FolderChooser = FolderChooser { FolderChoice.Cancelled }, settings: AppSettingsRepository = FakeAppSettingsRepository(), diagnostics: RecordingDiagnostics = RecordingDiagnostics()) =
    NewTaskViewModel(chats, board, specs, branches, folders, FakeExecutionChatRepository(), detecting(), FIXED_CLOCK, settings, diagnostics)

  @Test
  fun `branch choice is explicit and a failed default switch preserves it and reports the error`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chats = testNewTaskChats()
    val draft = chats.selected.value
    draft.repository = NewTaskRepository("/repo", "repo")
    var current = "feature/current"
    var refuseSwitch = true
    val branches = object : RepositoryBranchesRepository {
      override suspend fun branches(path: String) = RepositoryBranchesRead.Listed(
        RepositoryBranches(path, "main", emptyList(), emptyList(), current),
      )
      override suspend fun switchToDefaultBranch(path: String, expectedCurrentBranch: String?): Outcome<String, ReadProblem> {
        assertEquals("feature/current", expectedCurrentBranch)
        if (refuseSwitch) return Outcome.Failure(ReadProblem.Runtime("Local edits would be overwritten"))
        current = "main"
        return Outcome.Success(path)
      }
    }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, branches = branches)
    runCurrent()
    assertEquals("", draft.baseBranch)
    model.onAction(NewTaskAction.UseCurrentBranchAsBase)
    assertEquals("feature/current", draft.baseBranch)
    model.onAction(NewTaskAction.SwitchToDefaultBranch)
    runCurrent()
    assertEquals("feature/current", draft.baseBranch)
    assertEquals("feature/current", (model.state.value.branchRead as BranchReadState.Loaded).branches.currentBranch)
    assertTrue(model.state.value.repositoryError.orEmpty().contains("Local edits would be overwritten"))

    refuseSwitch = false
    model.onAction(NewTaskAction.SwitchToDefaultBranch)
    runCurrent()
    assertEquals("", draft.baseBranch)
    assertEquals("main", (model.state.value.branchRead as BranchReadState.Loaded).branches.currentBranch)
    assertEquals(null, model.state.value.repositoryError)
    model.viewModelScope.cancel()
  }

  @Test
  fun `switch to default worktree rebinds the chat and preserves its conversation and draft`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val original = createTempDirectory("feature-checkout").toString()
    val main = createTempDirectory("main-checkout").toString()
    val chat = FakeExecutionChatRepository()
    val chats = testNewTaskChats(chat)
    val draft = chats.selected.value.apply { repository = NewTaskRepository(original, "repo") }
    val branches = object : RepositoryBranchesRepository {
      override suspend fun branches(path: String) = RepositoryBranchesRead.Listed(
        RepositoryBranches(path, "main", emptyList(), emptyList(), if (path == main) "main" else "feature/current"),
      )
      override suspend fun switchToDefaultBranch(path: String, expectedCurrentBranch: String?): Outcome<String, ReadProblem> {
        assertEquals(original, path)
        assertEquals("feature/current", expectedCurrentBranch)
        return Outcome.Success(main)
      }
    }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, branches = branches)
    runCurrent()
    draft.text = "Explain the sharing flow"
    model.onAction(NewTaskAction.Send)
    runCurrent()
    val before = checkNotNull(draft.chat.value)
    draft.text = "Keep this unsent draft"

    model.onAction(NewTaskAction.SwitchToDefaultBranch)
    runCurrent()

    val after = checkNotNull(draft.chat.value)
    assertEquals(main, draft.repository?.root)
    assertEquals(main, after.repositoryRoot)
    assertNotEquals(before.sessionId, after.sessionId)
    assertTrue(after.thread.transcript.value.filterIsInstance<ChatMessage.Operator>().any { it.text == "Explain the sharing flow" })
    assertEquals("Keep this unsent draft", draft.text)
    assertEquals("main", (model.state.value.branchRead as BranchReadState.Loaded).branches.currentBranch)
    model.viewModelScope.cancel()
  }

  @Test
  fun `quick operation asks for branch choice only when started and respects the explicit current base`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val controls = FakeWorkControlRepository()
    val chats = testNewTaskChats(controls = controls)
    val draft = chats.selected.value.apply { repository = NewTaskRepository("/repo", "repo") }
    val branches = FakeBranches { path -> RepositoryBranchesRead.Listed(
      RepositoryBranches(path, "main", emptyList(), emptyList(), "feature/current"),
    ) }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, branches = branches)
    runCurrent()
    assertEquals(false, draft.branchChoiceRequested)
    draft.text = "Explain sharing"
    model.onAction(NewTaskAction.Send)
    runCurrent()

    model.start(NewTaskKind.Verify, "Check sharing", null)
    runCurrent()
    assertEquals(true, draft.branchChoiceRequested)
    assertTrue(controls.newTaskRequests.isEmpty())

    model.onAction(NewTaskAction.RunSuggestedAction(ChatSuggestedAction("Explain", "Explain the flow", ChatPermissionMode.ReadOnly)))
    runCurrent()
    assertEquals(false, draft.branchChoiceRequested)
    model.start(NewTaskKind.Verify, "Check sharing", null)
    model.onAction(NewTaskAction.UseCurrentBranchAsBase)
    model.start(NewTaskKind.Verify, "Check sharing", null)
    runCurrent()
    assertEquals("feature/current", controls.newTaskRequests.single().baseBranch)
    model.viewModelScope.cancel()
  }

  @Test
  fun `model selection keeps an existing draft session and applies to its next message`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val repository = FakeExecutionChatRepository()
    val chats = testNewTaskChats(chat = repository)
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats)
    runCurrent()
    val draft = chats.selected.value
    draft.repository = NewTaskRepository("/repo", "repo")
    draft.text = "original question"
    draft.send()
    runCurrent()
    val previous = repository.requests.single()

    model.onAction(NewTaskAction.ChooseModel(ChatModel("new-model", "New model")))
    runCurrent()
    val selected = draft.selectedAgent!!
    assertEquals("new-model", selected.model)
    assertEquals("new-model", repository.saved.values.single().agent.model)
    draft.text = "follow-up question"
    draft.send()
    runCurrent()

    val next = repository.requests.last()
    assertEquals(selected, next.agent)
    assertEquals(previous.sessionId, next.sessionId)
    assertEquals("follow-up question", next.prompt)
    model.viewModelScope.cancel()
    chats.closeAll()
  }

  @Test
  fun `model selection during a pending agent switch does not cancel resolution or block sending`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val repository = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val resolved = CompletableDeferred<ChatAgent>()
    val gated = object : ExecutionChatRepository by repository {
      override suspend fun agentFor(agentId: String): ChatAgent =
        if (agentId == "codex") resolved.await() else repository.agentFor(agentId)
    }
    val chats = testNewTaskChats(gated)
    val model = NewTaskViewModel(chats, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, gated, detecting("claude"), FIXED_CLOCK, FakeAppSettingsRepository(), RecordingDiagnostics())
    runCurrent()
    val draft = chats.selected.value
    draft.repository = NewTaskRepository("/repo", "repo")
    val previous = draft.selectedAgent
    model.onAction(NewTaskAction.ChooseAgent("codex"))
    runCurrent()
    model.onAction(NewTaskAction.ChooseModel(ChatModel("old-agent-model", "Old agent model")))
    assertEquals(previous, draft.selectedAgent)

    val codex = ChatAgent("codex", "codex-model", null)
    resolved.complete(codex)
    runCurrent()
    assertEquals(codex, draft.selectedAgent)
    draft.text = "continue"
    model.onAction(NewTaskAction.Send)
    runCurrent()
    assertEquals(codex, repository.requests.single().agent)
    model.viewModelScope.cancel()
    chats.closeAll()
  }

  @Test
  fun `chat layout follows settings without changing open or selected chats`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chats = testNewTaskChats()
    val second = chats.newChat()
    val open = chats.open.value
    val settings = FakeAppSettingsRepository()
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, settings = settings)
    runCurrent()

    assertEquals(NewTaskChatLayout.Tabs, model.state.value.newTaskChatLayout)
    settings.settings.value = settings.settings.value.copy(newTaskChatLayout = NewTaskChatLayout.SideList)
    runCurrent()

    assertEquals(NewTaskChatLayout.SideList, model.state.value.newTaskChatLayout)
    assertEquals(open, chats.open.value)
    assertEquals(second, chats.selected.value)
    model.viewModelScope.cancel()
  }

  @Test
  fun `an unavailable chat layout records the fallback and keeps the default`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val diagnostics = RecordingDiagnostics()
    val settings = object : AppSettingsRepository by FakeAppSettingsRepository() {
      override val settings: Flow<AppSettings> = flow { throw IllegalStateException("Settings unavailable") }
    }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), testNewTaskChats(), settings = settings, diagnostics = diagnostics)
    runCurrent()

    assertEquals(NewTaskChatLayout.Tabs, model.state.value.newTaskChatLayout)
    assertEquals(null, model.state.value.qaDeviceName)
    assertEquals(listOf("desktop_new_task_chat_layout_unavailable", "desktop_qa_device_pick_unavailable", "desktop_default_agent_unavailable"), diagnostics.categories)
    model.viewModelScope.cancel()
  }

  @Test
  fun `discarding a closed draft deletes it and show all opens the picker`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chatRepo = FakeExecutionChatRepository()
    val entry = ChatHistoryEntry(
      "saved-draft",
      null,
      requireNotNull(chatRepo.agent),
      Instant.fromEpochSeconds(1),
      "Saved task",
      listOf(ChatHistoryLine(ChatHistoryRole.Operator, "Saved task")),
      ChatNewTaskContext("/work/app", "main"),
    )
    chatRepo.saved[entry.sessionId] = entry
    val chats = NewTaskChats(chatRepo, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK)
    val draft = chats.selected.value
    val model = NewTaskViewModel(chats, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chatRepo, detecting("claude"), FIXED_CLOCK, FakeAppSettingsRepository(), RecordingDiagnostics())
    runCurrent()
    draft.refreshSaved()
    runCurrent()

    assertEquals(listOf(entry), draft.saved.value)

    model.onAction(NewTaskAction.DiscardDraft(entry))
    runCurrent()
    assertTrue(entry.sessionId in chatRepo.saved)
    advanceTimeBy(5_000)
    runCurrent()
    assertTrue(entry.sessionId !in chatRepo.saved)
    assertTrue(draft.saved.value.isEmpty())

    draft.text = "keep this text"
    model.onAction(NewTaskAction.ShowAllDrafts)
    runCurrent()
    assertTrue(model.state.value.resumePickerOpen)
    assertEquals("keep this text", draft.text)
    model.viewModelScope.cancel()
  }

  // View models die on sidebar navigation, so state kept in one would lose the chat.
  @Test
  fun `a chat outlives the view model that sent it`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val events = MutableSharedFlow<ChatEvent>()
    val chatRepository = FakeExecutionChatRepository().apply { this.events = { events } }
    val chats = NewTaskChats(chatRepository, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK)
    val draft = chats.selected.value.apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/app", "app")
      agentId = "claude"
      text = "add export"
    }
    val workspace = ChatWorkspace(chatRepository, FakeWorkDetailRepository(), FakeWorkControlRepository(), testDatabaseSessionController(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK, ShellPanes(), SelectionHolder(), chats)
    val first = viewModel(FakeWorkBoardRepository(loadedBoard()), chats)
    advanceUntilIdle()
    first.onAction(NewTaskAction.Send)
    runCurrent()
    val chat = draft.chat.value
    first.viewModelScope.cancel()
    assertTrue(workspace.newTaskDraftOpen.value)
    assertTrue(checkNotNull(chat).thread.busy.value)
    events.emit(ChatEvent.TextDelta("export ready"))
    events.emit(ChatEvent.Completed)
    runCurrent()

    val second = viewModel(FakeWorkBoardRepository(loadedBoard()), chats)
    advanceUntilIdle()

    assertEquals(chat, second.draft.chat.value)
    assertEquals("export ready", (chat.thread.transcript.value.last() as ChatMessage.Agent).text)
    second.viewModelScope.cancel()
    draft.close()
  }

  @Test
  fun `a failed path check keeps the selected repository and the picker open with the error`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val previous = NewTaskRepository("/work/previous", "previous")
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = previous }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, FakeSpecs(pathRead = Outcome.Failure(ReadProblem.Runtime("No such repository"))))
    advanceUntilIdle()

    model.onAction(NewTaskAction.OpenRepositoryPicker)
    model.onAction(NewTaskAction.SubmitRepositoryPath("/missing"))
    advanceUntilIdle()

    assertEquals(previous, draft.repository)
    assertTrue(model.state.value.repositoryPickerOpen)
    assertEquals("No such repository", model.state.value.repositoryError)
    model.viewModelScope.cancel()
  }

  @Test
  fun `the default repository comes from the first board row and a reordered board keeps it`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val first = workItem("SKILL-1").copy(repositoryRoot = "/work/first", repositoryName = "first")
    val second = workItem("SKILL-2").copy(repositoryRoot = "/work/second", repositoryName = "second")
    val board = FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(first, second))))
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value
    val model = viewModel(board, chats)
    advanceUntilIdle()

    board.state.value = loadedBoard(mapOf(BoardSectionKind.Goals to listOf(second, first)))
    advanceUntilIdle()

    assertEquals(NewTaskRepository("/work/first", "first"), draft.repository)
    model.viewModelScope.cancel()
  }

  @Test
  fun `closing the picker abandons a path check still in flight`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val previous = NewTaskRepository("/work/previous", "previous")
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = previous }
    val gate = Job()
    val specs = FakeSpecs(pathRead = Outcome.Success(SpecProject("slow", "slow", "/specs/slow", "/work/slow")), pathGate = gate)
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, specs)
    advanceUntilIdle()

    model.onAction(NewTaskAction.OpenRepositoryPicker)
    model.onAction(NewTaskAction.SubmitRepositoryPath("/work/slow"))
    advanceUntilIdle()
    model.onAction(NewTaskAction.CloseRepositoryPicker)
    gate.complete()
    advanceUntilIdle()

    assertEquals(previous, draft.repository)
    model.viewModelScope.cancel()
  }

  @Test
  fun `a board without rows falls back to the first project with a repository root`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val projects = listOf(SpecProject("rootless", "rootless", "/specs/rootless", null), SpecProject("rooted", "rooted", "/specs/rooted", "/work/rooted"))
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, FakeSpecs(listed = projects))
    advanceUntilIdle()

    val rooted = NewTaskRepository("/work/rooted", "rooted")
    assertEquals(rooted, draft.repository)
    assertEquals(listOf(rooted), model.state.value.repositories)
    model.viewModelScope.cancel()
  }

  @Test
  fun `the default agent is the first listed one the machine has`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value
    val chat = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val model = NewTaskViewModel(chats, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chat, detecting("Codex"), FIXED_CLOCK, FakeAppSettingsRepository(), RecordingDiagnostics())
    advanceUntilIdle()

    assertEquals("codex", draft.agentId)
    assertEquals("codex", model.state.value.agent?.agentId)
    model.viewModelScope.cancel()
  }

  @Test
  fun `a fresh draft starts on the default agent and follows it until the operator picks one`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value
    val settings = FakeAppSettingsRepository()
    settings.setDefaultAgent("claude")
    val chat = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val model = NewTaskViewModel(chats, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chat, detecting("Codex"), FIXED_CLOCK, settings, RecordingDiagnostics())
    advanceUntilIdle()
    assertEquals("claude", draft.agentId)

    settings.setDefaultAgent("codex")
    advanceUntilIdle()
    assertEquals("codex", draft.agentId)

    model.onAction(NewTaskAction.ChooseAgent("claude"))
    advanceUntilIdle()
    settings.setDefaultAgent("codex")
    settings.setDefaultAgent(null)
    advanceUntilIdle()
    assertEquals("claude", draft.agentId)
    model.viewModelScope.cancel()
  }

  @Test
  fun `the home follows the board after the default repository is chosen`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val board = FakeWorkBoardRepository(BoardState.Loading)
    val model = NewTaskViewModel(unconfinedNewTaskChats(), board, FakeSpecs(listed = listOf(SpecProject("p", "p", "/specs/p", "/work/p"))), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, FakeExecutionChatRepository(), detecting("claude"), FIXED_CLOCK, FakeAppSettingsRepository(), RecordingDiagnostics())
    advanceUntilIdle()
    assertEquals(NewTaskHome.Pending, model.state.value.home)

    board.state.value = loadedBoard()
    advanceUntilIdle()
    assertEquals(NewTaskHome.FirstRun, model.state.value.home)
    assertEquals(DetectedAgentsRead.Success(listOf(DetectedAgent("claude", "/bin/claude"))), model.state.value.detectedAgents)

    val running = workItem("SKILL-1")
    board.state.value = loadedBoard(mapOf(BoardSectionKind.Goals to listOf(running)))
    advanceUntilIdle()
    assertEquals(NewTaskHome.Returning(emptyList(), running = 0, finished = 0), model.state.value.home)
    model.viewModelScope.cancel()
  }

  @Test
  fun `choosing another agent after a chat rebinds that chat`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chatRepo = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val chats = NewTaskChats(chatRepo, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK)
    val draft = chats.selected.value.apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository(createTempDirectory("new-task-repo").toString(), "repo")
      agentId = "claude"
      selectedAgent = chatRepo.agent
      text = "add export"
    }
    val model = NewTaskViewModel(chats, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chatRepo, detecting("claude"), FIXED_CLOCK, FakeAppSettingsRepository(), RecordingDiagnostics())
    advanceUntilIdle()
    model.onAction(NewTaskAction.Send)
    runCurrent()
    val firstSession = chatRepo.requests.single().sessionId

    model.onAction(NewTaskAction.ChooseAgent("codex"))
    assertEquals(chatRepo.agent, draft.selectedAgent)
    assertEquals(chipText(chatRepo.agent), chipText(model.state.value.agent))
    advanceUntilIdle()

    assertEquals("codex", draft.agentId)
    assertEquals("codex", model.state.value.agent?.agentId)
    assertEquals(chipText(chatRepo.agentFor("codex")), chipText(model.state.value.agent))
    assertEquals(chipText(chatRepo.agentFor("codex")), chipText(draft.selectedAgent))
    assertNotEquals(firstSession, draft.chat.value?.sessionId)
    assertEquals("codex", draft.chat.value?.agent?.agentId)
    model.viewModelScope.cancel()
    draft.close()
  }

  // An agent resolve that writes into, or rebinds, whichever chat is selected when it finishes.
  @Test
  fun `an agent resolve started in one chat that finishes after switching chats rebinds only that chat`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chatRepo = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val codexRead = CompletableDeferred<Unit>()
    val gated = object : ExecutionChatRepository by chatRepo {
      override suspend fun agentFor(agentId: String): ChatAgent {
        if (agentId == "codex") codexRead.await()
        return chatRepo.agentFor(agentId)
      }
    }
    val chats = testNewTaskChats(gated)
    val a = chats.selected.value.apply {
      repository = NewTaskRepository(createTempDirectory("new-task-repo").toString(), "repo")
      agentId = "claude"
      selectedAgent = chatRepo.agent
      text = "add export"
    }
    val model = NewTaskViewModel(chats, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, gated, detecting("claude"), FIXED_CLOCK, FakeAppSettingsRepository(), RecordingDiagnostics())
    advanceUntilIdle()
    model.onAction(NewTaskAction.Send)
    runCurrent()
    val firstSession = checkNotNull(a.chat.value).sessionId

    model.onAction(NewTaskAction.NewChat)
    val b = chats.selected.value
    chats.select(a)
    runCurrent()

    model.onAction(NewTaskAction.ChooseAgent("codex"))
    runCurrent()
    chats.select(b)
    runCurrent()
    codexRead.complete(Unit)
    advanceUntilIdle()

    assertEquals("codex", a.chat.value?.agent?.agentId)
    assertNotEquals(firstSession, a.chat.value?.sessionId)
    assertEquals("claude", b.agentId)
    assertEquals("claude", b.selectedAgent?.agentId)
    assertEquals(null, b.chat.value)
    assertEquals("claude", model.state.value.agent?.agentId)
    model.viewModelScope.cancel()
    a.close()
  }

  @Test
  fun `a branch read for an earlier repository does not replace the current one`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val gate = CompletableDeferred<Unit>()
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = NewTaskRepository("/work/a", "a") }
    val branches = FakeBranches { path ->
      if (path == "/work/a") gate.await()
      listed(path)
    }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, branches = branches)
    runCurrent()

    model.onAction(NewTaskAction.ChooseRepository(NewTaskRepository("/work/b", "b")))
    advanceUntilIdle()
    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(BranchReadState.Loaded("/work/b", listed("/work/b").branches), model.state.value.branchRead)
    model.viewModelScope.cancel()
  }

  @Test
  fun `a failed branch read reports failed and reopening reads again`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val problem = ReadProblem.Runtime("git failed")
    val reads = ArrayDeque(listOf(RepositoryBranchesRead.Unavailable(problem), listed("/work/app"), RepositoryBranchesRead.NotARepository("/work/app")))
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = NewTaskRepository("/work/app", "app") }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, branches = FakeBranches { reads.removeFirst() })
    advanceUntilIdle()
    assertEquals(BranchReadState.Failed("/work/app", readProblemText(problem)), model.state.value.branchRead)

    model.onAction(NewTaskAction.OpenRepositoryPicker)
    assertEquals(BranchReadState.Reading("/work/app"), model.state.value.branchRead)
    advanceUntilIdle()
    val loaded = BranchReadState.Loaded("/work/app", listed("/work/app").branches)
    assertEquals(loaded, model.state.value.branchRead)

    model.onAction(NewTaskAction.OpenRepositoryPicker)
    assertEquals(loaded, model.state.value.branchRead)
    advanceUntilIdle()
    assertEquals(BranchReadState.Failed("/work/app", "/work/app is not a git repository."), model.state.value.branchRead)
    model.viewModelScope.cancel()
  }

  @Test
  fun `choosing another repository resets the branch and keeps the picker open while the same one keeps it`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = NewTaskRepository("/work/app", "app")
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = app; baseBranch = "develop" }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats)
    advanceUntilIdle()
    model.onAction(NewTaskAction.OpenRepositoryPicker)

    model.onAction(NewTaskAction.ChooseRepository(app))
    assertEquals("develop", draft.baseBranch)

    model.onAction(NewTaskAction.ChooseRepository(NewTaskRepository("/work/other", "other")))
    assertEquals("", draft.baseBranch)
    assertTrue(model.state.value.repositoryPickerOpen)
    model.viewModelScope.cancel()
  }

  @Test
  fun `repository and branch picker changes after a reply rebind and replay the chat`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chatRepo = FakeExecutionChatRepository().apply { events = { flowOf(ChatEvent.TextDelta("answer"), ChatEvent.Completed) } }
    val chats = NewTaskChats(chatRepo, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK)
    val repository = NewTaskRepository(createTempDirectory("new-task-repo").toString(), "repo")
    val draft = chats.selected.value.apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      this.repository = repository
      agentId = "claude"
      selectedAgent = chatRepo.agent
      text = "add export"
    }
    val model = NewTaskViewModel(chats, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chatRepo, detecting("claude"), FIXED_CLOCK, FakeAppSettingsRepository(), RecordingDiagnostics())
    runCurrent()
    model.onAction(NewTaskAction.Send)
    runCurrent()
    val initialChat = checkNotNull(draft.chat.value)
    assertEquals("answer", (initialChat.thread.transcript.value.last() as ChatMessage.Agent).text)
    assertEquals(false, initialChat.thread.busy.value)
    val firstSession = chatRepo.requests.single().sessionId
    val otherRepository = NewTaskRepository(createTempDirectory("other-new-task-repo").toString(), "other")

    model.onAction(NewTaskAction.ChooseRepository(otherRepository))
    model.onAction(NewTaskAction.ChooseBaseBranch("origin/release"))
    runCurrent()

    val rebound = checkNotNull(draft.chat.value)
    assertNotEquals(firstSession, rebound.sessionId)
    assertEquals(otherRepository.root, rebound.repositoryRoot)
    assertEquals("origin/release", rebound.baseBranch)
    assertEquals("add export", (rebound.thread.transcript.value.first() as ChatMessage.Operator).text)
    assertTrue(rebound.thread.transcript.value.any { it is ChatMessage.Notice && it.text.contains("earlier conversation") })
    draft.text = "continue"
    model.onAction(NewTaskAction.Send)
    runCurrent()
    val next = chatRepo.requests.last()
    assertEquals(rebound.sessionId, next.sessionId)
    assertEquals(dev.skillbill.desktop.core.domain.model.ChatScope.NewTask(otherRepository.root, "origin/release"), next.scope)
    assertTrue(next.prompt.contains("Earlier in this chat"))
    assertTrue(next.prompt.contains("The repository changed from ${repository.root} to ${otherRepository.root}"))
    assertTrue(next.prompt.contains("The base branch changed"))
    model.viewModelScope.cancel()
    draft.close()
  }

  @Test
  fun `choosing a base branch closes the picker and retry reads the selected root again`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val repository = NewTaskRepository("/work/app", "app")
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { this.repository = repository }
    val branches = FakeBranches()
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, branches = branches)
    advanceUntilIdle()
    model.onAction(NewTaskAction.OpenRepositoryPicker)
    advanceUntilIdle()
    model.onAction(NewTaskAction.ChooseRepository(repository))
    assertEquals(1, model.state.value.repositoryPicks)
    model.onAction(NewTaskAction.RetryBranches)
    advanceUntilIdle()

    assertEquals(listOf("/work/app", "/work/app", "/work/app"), branches.calls)
    assertEquals(BranchReadState.Loaded("/work/app", listed("/work/app").branches), model.state.value.branchRead)
    model.onAction(NewTaskAction.ChooseBaseBranch("origin/rel"))

    assertEquals("origin/rel", draft.baseBranch)
    assertEquals(false, model.state.value.repositoryPickerOpen)
    model.viewModelScope.cancel()
  }

  @Test
  fun `a non-git folder errors without projectFor and a later subfolder adds its repository root`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val previous = NewTaskRepository("/work/previous", "previous")
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = previous }
    val specs = FakeSpecs(pathRead = Outcome.Success(SpecProject("app", "app", "/specs/app", "/work/app")))
    val branches = FakeBranches { path ->
      when (path) {
        "/tmp/plain" -> RepositoryBranchesRead.NotARepository(path)
        "/work/app/src" -> listed("/work/app")
        else -> listed(path)
      }
    }
    val choices = ArrayDeque(listOf(FolderChoice.Chosen("/tmp/plain"), FolderChoice.Chosen("/work/app/src")))
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, specs, branches, FolderChooser { choices.removeFirst() })
    advanceUntilIdle()
    model.onAction(NewTaskAction.OpenRepositoryPicker)

    model.onAction(NewTaskAction.AddRepository)
    advanceUntilIdle()
    assertEquals("/tmp/plain is not a git repository.", model.state.value.repositoryError)
    assertEquals(previous, draft.repository)
    assertTrue(model.state.value.repositoryPickerOpen)
    assertEquals(emptyList(), specs.projectForCalls)

    model.onAction(NewTaskAction.AddRepository)
    advanceUntilIdle()
    val app = NewTaskRepository("/work/app", "app")
    assertEquals(listOf("/work/app"), specs.projectForCalls)
    assertEquals(app, draft.repository)
    assertEquals(listOf(app), draft.addedRepositories)
    assertEquals(null, model.state.value.repositoryError)
    assertEquals(BranchReadState.Loaded("/work/app", listed("/work/app").branches), model.state.value.branchRead)
    assertEquals(1, model.state.value.repositoryPicks)
    assertTrue(model.state.value.repositoryPickerOpen)
    model.viewModelScope.cancel()
  }

  @Test
  fun `a projectFor failure shows the error and adds no row`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val previous = NewTaskRepository("/work/previous", "previous")
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = previous }
    val specs = FakeSpecs(pathRead = Outcome.Failure(ReadProblem.Runtime("store broke")))
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, specs, folders = FolderChooser { FolderChoice.Chosen("/work/app") })
    advanceUntilIdle()

    model.onAction(NewTaskAction.AddRepository)
    advanceUntilIdle()

    assertEquals("store broke", model.state.value.repositoryError)
    assertEquals(emptyList(), draft.addedRepositories)
    assertEquals(previous, draft.repository)
    model.viewModelScope.cancel()
  }

  @Test
  fun `an unreadable folder errors without projectFor and keeps the selection`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val previous = NewTaskRepository("/work/previous", "previous")
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = previous }
    val specs = FakeSpecs()
    val branches = FakeBranches { path -> if (path == "/tmp/locked") RepositoryBranchesRead.Unavailable(ReadProblem.Runtime("denied")) else listed(path) }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, specs, branches, FolderChooser { FolderChoice.Chosen("/tmp/locked") })
    advanceUntilIdle()

    model.onAction(NewTaskAction.AddRepository)
    advanceUntilIdle()

    assertEquals("Could not read /tmp/locked: denied", model.state.value.repositoryError)
    assertEquals(emptyList(), specs.projectForCalls)
    assertEquals(previous, draft.repository)
    model.viewModelScope.cancel()
  }

  @Test
  fun `cancelling the chooser leaves an earlier error and the selection alone`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val previous = NewTaskRepository("/work/previous", "previous")
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = previous; baseBranch = "develop" }
    val choices = ArrayDeque(listOf(FolderChoice.Failed("no display"), FolderChoice.Cancelled))
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, folders = FolderChooser { choices.removeFirst() })
    advanceUntilIdle()

    model.onAction(NewTaskAction.AddRepository)
    advanceUntilIdle()
    model.onAction(NewTaskAction.AddRepository)
    advanceUntilIdle()

    assertEquals("Could not open the folder chooser: no display", model.state.value.repositoryError)
    assertEquals(previous, draft.repository)
    assertEquals("develop", draft.baseBranch)
    model.viewModelScope.cancel()
  }

  @Test
  fun `close is ignored while the chooser is open and a second add does not reopen it`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = NewTaskRepository("/work/previous", "previous") }
    val choice = CompletableDeferred<FolderChoice>()
    var opened = 0
    val branches = FakeBranches { path -> if (path == "/tmp/plain") RepositoryBranchesRead.NotARepository(path) else listed(path) }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), chats, branches = branches, folders = FolderChooser { opened++; choice.await() })
    advanceUntilIdle()
    model.onAction(NewTaskAction.OpenRepositoryPicker)

    model.onAction(NewTaskAction.AddRepository)
    model.onAction(NewTaskAction.AddRepository)
    advanceUntilIdle()
    assertTrue(model.state.value.choosingFolder)
    assertEquals(1, opened)
    model.onAction(NewTaskAction.CloseRepositoryPicker)
    assertTrue(model.state.value.repositoryPickerOpen)

    choice.complete(FolderChoice.Chosen("/tmp/plain"))
    advanceUntilIdle()
    assertEquals(false, model.state.value.choosingFolder)
    assertTrue(model.state.value.repositoryPickerOpen)
    assertEquals("/tmp/plain is not a git repository.", model.state.value.repositoryError)
    model.viewModelScope.cancel()
  }

  @Test
  fun `stop returns queued text and send now sends a queued message`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chatRepo = FakeExecutionChatRepository()
    val replies = mutableListOf<Channel<ChatEvent>>()
    chatRepo.events = {
      val reply = Channel<ChatEvent>(Channel.UNLIMITED)
      replies += reply
      flow { for (event in reply) emit(event) }
    }
    val chats = NewTaskChats(chatRepo, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK)
    val draft = chats.selected.value.apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository(createTempDirectory("new-task-repo").toString(), "repo")
      agentId = "claude"
      selectedAgent = chatRepo.agent
    }
    val model = NewTaskViewModel(chats, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chatRepo, detecting("claude"), FIXED_CLOCK, FakeAppSettingsRepository(), RecordingDiagnostics())
    advanceUntilIdle()
    draft.text = "first"
    model.onAction(NewTaskAction.Send)
    runCurrent()
    draft.text = "queued"
    model.onAction(NewTaskAction.Send)
    runCurrent()
    model.onAction(NewTaskAction.Stop)
    runCurrent()
    assertEquals("queued", draft.text)
    replies[0].close()
    runCurrent()
    draft.text = "again"
    model.onAction(NewTaskAction.Send)
    runCurrent()
    draft.text = "now"
    model.onAction(NewTaskAction.Send)
    runCurrent()
    model.onAction(NewTaskAction.SendNow)
    runCurrent()
    replies[1].close()
    runCurrent()
    replies[2].send(ChatEvent.Completed)
    replies[2].close()
    runCurrent()
    assertEquals(listOf("first", "again", "now"), chatRepo.requests.map { it.prompt })
    model.viewModelScope.cancel()
    draft.close()
  }

  private fun detecting(vararg names: String) = Proxy.newProxyInstance(
    RuntimeConfigRepository::class.java.classLoader,
    arrayOf(RuntimeConfigRepository::class.java),
  ) { _, method, _ ->
    check(method.name == "detectedAgentsRead") { "unexpected ${method.name}" }
    DetectedAgentsRead.Success(names.map { DetectedAgent(it, "/bin/$it") })
  } as RuntimeConfigRepository

  private companion object {
    fun listed(root: String) = RepositoryBranchesRead.Listed(RepositoryBranches(root, "main", emptyList(), emptyList()))

    val FIXED_CLOCK = object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(0)
    }
  }

  private class FakeBranches(private val read: suspend (String) -> RepositoryBranchesRead = { listed(it) }) : RepositoryBranchesRepository {
    val calls = mutableListOf<String>()

    override suspend fun switchToDefaultBranch(path: String, expectedCurrentBranch: String?): Outcome<String, ReadProblem> = Outcome.Success(path)

    override suspend fun branches(path: String): RepositoryBranchesRead {
      calls += path
      return read(path)
    }
  }

  private class FakeSpecs(
    private val listed: List<SpecProject> = emptyList(),
    private val pathRead: Outcome<SpecProject, ReadProblem> = Outcome.Failure(ReadProblem.Runtime("unused")),
    private val pathGate: Job? = null,
  ) : SpecRepository {
    val projectForCalls = mutableListOf<String>()

    override suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> = Outcome.Success(listed)
    override suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem> {
      projectForCalls += repoRoot
      pathGate?.join()
      return pathRead
    }

    override fun observeTree(projectId: String): Flow<SpecTreeState> = emptyFlow()
    override fun refreshTree(projectId: String) = Unit
    override fun control(bundle: SpecBundleRef): Flow<SpecControlState> = emptyFlow()
    override suspend fun openFile(path: String): OpenOutcome = error("unused")
    override suspend fun openFileInDefaultApp(path: String): OpenOutcome = error("unused")
    override suspend fun openFileUnder(path: String, directory: String): OpenOutcome = error("unused")
    override suspend fun revealFolder(path: String): OpenOutcome = error("unused")
    override suspend fun deleteBundle(bundle: SpecBundleRef): SpecDeleteOutcome = error("unused")
  }
}
