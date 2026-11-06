package dev.skillbill.desktop.feature.chat.newtask

import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
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
import dev.skillbill.desktop.core.domain.repository.FolderChoice
import dev.skillbill.desktop.core.domain.repository.FolderChooser
import dev.skillbill.desktop.core.domain.repository.RepositoryBranchesRepository
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
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

  private fun viewModel(board: FakeWorkBoardRepository, draft: NewTaskDraft, specs: SpecRepository = FakeSpecs(), branches: RepositoryBranchesRepository = FakeBranches(), folders: FolderChooser = FolderChooser { FolderChoice.Cancelled }) =
    NewTaskViewModel(draft, board, specs, branches, folders, FakeExecutionChatRepository(), detecting(), FIXED_CLOCK)

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
    val draft = NewTaskDraft(chatRepo, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK)
    val model = NewTaskViewModel(draft, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chatRepo, detecting("claude"), FIXED_CLOCK)
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
    val draft = NewTaskDraft(chatRepository, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository("/work/app", "app")
      agentId = "claude"
      text = "add export"
    }
    val workspace = ChatWorkspace(chatRepository, FakeWorkDetailRepository(), FakeWorkControlRepository(), testDatabaseSessionController(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK, ShellPanes(), SelectionHolder(), draft)
    val first = viewModel(FakeWorkBoardRepository(loadedBoard()), draft)
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

    val second = viewModel(FakeWorkBoardRepository(loadedBoard()), draft)
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
    val draft = testNewTaskDraft().apply { repository = previous }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, FakeSpecs(pathRead = Outcome.Failure(ReadProblem.Runtime("No such repository"))))
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
    val draft = testNewTaskDraft()
    val model = viewModel(board, draft)
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
    val draft = testNewTaskDraft().apply { repository = previous }
    val gate = Job()
    val specs = FakeSpecs(pathRead = Outcome.Success(SpecProject("slow", "slow", "/specs/slow", "/work/slow")), pathGate = gate)
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, specs)
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
    val draft = testNewTaskDraft()
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, FakeSpecs(listed = projects))
    advanceUntilIdle()

    val rooted = NewTaskRepository("/work/rooted", "rooted")
    assertEquals(rooted, draft.repository)
    assertEquals(listOf(rooted), model.state.value.repositories)
    model.viewModelScope.cancel()
  }

  @Test
  fun `the default agent is the first listed one the machine has`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val draft = testNewTaskDraft()
    val chat = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val model = NewTaskViewModel(draft, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chat, detecting("Codex"), FIXED_CLOCK)
    advanceUntilIdle()

    assertEquals("codex", draft.agentId)
    assertEquals("codex", model.state.value.agent?.agentId)
    model.viewModelScope.cancel()
  }

  @Test
  fun `the home follows the board after the default repository is chosen`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val board = FakeWorkBoardRepository(BoardState.Loading)
    val model = NewTaskViewModel(testNewTaskDraft(), board, FakeSpecs(listed = listOf(SpecProject("p", "p", "/specs/p", "/work/p"))), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, FakeExecutionChatRepository(), detecting("claude"), FIXED_CLOCK)
    advanceUntilIdle()
    assertEquals(NewTaskHome.Pending, model.state.value.home)

    board.state.value = loadedBoard()
    advanceUntilIdle()
    assertEquals(NewTaskHome.FirstRun, model.state.value.home)
    assertEquals(DetectedAgentsRead.Success(listOf(DetectedAgent("claude", "/bin/claude"))), model.state.value.detectedAgents)

    val running = workItem("SKILL-1")
    board.state.value = loadedBoard(mapOf(BoardSectionKind.Goals to listOf(running)))
    advanceUntilIdle()
    assertEquals(NewTaskHome.Returning(emptyList(), running = 0, finished = 0, runningItems = listOf(running)), model.state.value.home)
    model.viewModelScope.cancel()
  }

  @Test
  fun `choosing another agent after a chat rebinds that chat`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val chatRepo = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val draft = NewTaskDraft(chatRepo, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository(createTempDirectory("new-task-repo").toString(), "repo")
      agentId = "claude"
      selectedAgent = chatRepo.agent
      text = "add export"
    }
    val model = NewTaskViewModel(draft, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chatRepo, detecting("claude"), FIXED_CLOCK)
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

  @Test
  fun `a branch read for an earlier repository does not replace the current one`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val gate = CompletableDeferred<Unit>()
    val draft = testNewTaskDraft().apply { repository = NewTaskRepository("/work/a", "a") }
    val branches = FakeBranches { path ->
      if (path == "/work/a") gate.await()
      listed(path)
    }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, branches = branches)
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
    val draft = testNewTaskDraft().apply { repository = NewTaskRepository("/work/app", "app") }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, branches = FakeBranches { reads.removeFirst() })
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
    val draft = testNewTaskDraft().apply { repository = app; baseBranch = "develop" }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft)
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
  fun `choosing a base branch closes the picker and retry reads the selected root again`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val repository = NewTaskRepository("/work/app", "app")
    val draft = testNewTaskDraft().apply { this.repository = repository }
    val branches = FakeBranches()
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, branches = branches)
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
    val draft = testNewTaskDraft().apply { repository = previous }
    val specs = FakeSpecs(pathRead = Outcome.Success(SpecProject("app", "app", "/specs/app", "/work/app")))
    val branches = FakeBranches { path ->
      when (path) {
        "/tmp/plain" -> RepositoryBranchesRead.NotARepository(path)
        "/work/app/src" -> listed("/work/app")
        else -> listed(path)
      }
    }
    val choices = ArrayDeque(listOf(FolderChoice.Chosen("/tmp/plain"), FolderChoice.Chosen("/work/app/src")))
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, specs, branches, FolderChooser { choices.removeFirst() })
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
    val draft = testNewTaskDraft().apply { repository = previous }
    val specs = FakeSpecs(pathRead = Outcome.Failure(ReadProblem.Runtime("store broke")))
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, specs, folders = FolderChooser { FolderChoice.Chosen("/work/app") })
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
    val draft = testNewTaskDraft().apply { repository = previous }
    val specs = FakeSpecs()
    val branches = FakeBranches { path -> if (path == "/tmp/locked") RepositoryBranchesRead.Unavailable(ReadProblem.Runtime("denied")) else listed(path) }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, specs, branches, FolderChooser { FolderChoice.Chosen("/tmp/locked") })
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
    val draft = testNewTaskDraft().apply { repository = previous; baseBranch = "develop" }
    val choices = ArrayDeque(listOf(FolderChoice.Failed("no display"), FolderChoice.Cancelled))
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, folders = FolderChooser { choices.removeFirst() })
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
    val draft = testNewTaskDraft().apply { repository = NewTaskRepository("/work/previous", "previous") }
    val choice = CompletableDeferred<FolderChoice>()
    var opened = 0
    val branches = FakeBranches { path -> if (path == "/tmp/plain") RepositoryBranchesRead.NotARepository(path) else listed(path) }
    val model = viewModel(FakeWorkBoardRepository(loadedBoard()), draft, branches = branches, folders = FolderChooser { opened++; choice.await() })
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
    val draft = NewTaskDraft(chatRepo, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      repository = NewTaskRepository(createTempDirectory("new-task-repo").toString(), "repo")
      agentId = "claude"
      selectedAgent = chatRepo.agent
    }
    val model = NewTaskViewModel(draft, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), FakeBranches(), FolderChooser { FolderChoice.Cancelled }, chatRepo, detecting("claude"), FIXED_CLOCK)
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
