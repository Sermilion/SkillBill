package dev.skillbill.desktop.feature.chat.newtask

import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.DetectedAgent
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecDeleteOutcome
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.ChatWorkspace
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskViewModelTest {
  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(board: FakeWorkBoardRepository, draft: NewTaskDraft, specs: SpecRepository = FakeSpecs()) =
    NewTaskViewModel(draft, board, specs, FakeExecutionChatRepository(), detecting(), FIXED_CLOCK)

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
    val workspace = ChatWorkspace(chatRepository, FakeWorkDetailRepository(), FakeWorkControlRepository(), WindowCoroutineScope(backgroundScope), FIXED_CLOCK, ShellPanes(), SelectionHolder(), draft)
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
    val model = NewTaskViewModel(draft, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), chat, detecting("Codex"), FIXED_CLOCK)
    advanceUntilIdle()

    assertEquals("codex", draft.agentId)
    assertEquals("codex", model.state.value.agent?.agentId)
    model.viewModelScope.cancel()
  }

  @Test
  fun `the home follows the board after the default repository is chosen`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val board = FakeWorkBoardRepository(BoardState.Loading)
    val model = NewTaskViewModel(testNewTaskDraft(), board, FakeSpecs(listed = listOf(SpecProject("p", "p", "/specs/p", "/work/p"))), FakeExecutionChatRepository(), detecting("claude"), FIXED_CLOCK)
    advanceUntilIdle()
    assertEquals(NewTaskHome.Pending, model.state.value.home)

    board.state.value = loadedBoard()
    advanceUntilIdle()
    assertEquals(NewTaskHome.FirstRun, model.state.value.home)
    assertEquals(DetectedAgentsRead.Success(listOf(DetectedAgent("claude", "/bin/claude"))), model.state.value.detectedAgents)

    board.state.value = loadedBoard(mapOf(BoardSectionKind.Goals to listOf(workItem("SKILL-1"))))
    advanceUntilIdle()
    assertEquals(NewTaskHome.Returning(emptyList(), running = 0, finished = 0), model.state.value.home)
    model.viewModelScope.cancel()
  }

  private fun detecting(vararg names: String) = Proxy.newProxyInstance(
    RuntimeConfigRepository::class.java.classLoader,
    arrayOf(RuntimeConfigRepository::class.java),
  ) { _, method, _ ->
    check(method.name == "detectedAgentsRead") { "unexpected ${method.name}" }
    DetectedAgentsRead.Success(names.map { DetectedAgent(it, "/bin/$it") })
  } as RuntimeConfigRepository

  private companion object {
    val FIXED_CLOCK = object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(0)
    }
  }

  private class FakeSpecs(
    private val listed: List<SpecProject> = emptyList(),
    private val pathRead: Outcome<SpecProject, ReadProblem> = Outcome.Failure(ReadProblem.Runtime("unused")),
    private val pathGate: Job? = null,
  ) : SpecRepository {
    override suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> = Outcome.Success(listed)
    override suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem> {
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
