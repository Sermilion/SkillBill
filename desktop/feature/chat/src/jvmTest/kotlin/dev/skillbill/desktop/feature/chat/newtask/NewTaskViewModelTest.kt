package dev.skillbill.desktop.feature.chat.newtask

import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
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
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskViewModelTest {
  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(board: FakeWorkBoardRepository, draft: NewTaskDraft, specs: SpecRepository = FakeSpecs()) =
    NewTaskViewModel(draft, board, specs, FakeExecutionChatRepository(), detecting())

  @Test
  fun `a failed path check keeps the selected repository and the picker open with the error`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val previous = NewTaskRepository("/work/previous", "previous")
    val draft = NewTaskDraft().apply { repository = previous }
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
    val draft = NewTaskDraft()
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
    val draft = NewTaskDraft().apply { repository = previous }
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
    val draft = NewTaskDraft()
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
    val draft = NewTaskDraft()
    val chat = FakeExecutionChatRepository(supported = setOf("claude", "codex"))
    val model = NewTaskViewModel(draft, FakeWorkBoardRepository(loadedBoard()), FakeSpecs(), chat, detecting("Codex"))
    advanceUntilIdle()

    assertEquals("codex", draft.agentId)
    assertEquals("codex", model.state.value.agent?.agentId)
    model.viewModelScope.cancel()
  }

  private fun detecting(vararg names: String) = Proxy.newProxyInstance(
    RuntimeConfigRepository::class.java.classLoader,
    arrayOf(RuntimeConfigRepository::class.java),
  ) { _, method, _ ->
    check(method.name == "detectedAgentsRead") { "unexpected ${method.name}" }
    DetectedAgentsRead.Success(names.map { DetectedAgent(it, "/bin/$it") })
  } as RuntimeConfigRepository

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
