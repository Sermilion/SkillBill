package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
  private val item = workItem("SKILL-1")
  private val details = RecordingDetailRepository()
  private val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) }

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  private val session = object : DatabaseSessionController {
    override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("test.db", 1))
    override fun reopen() = Unit
  }

  private fun viewModel(
    board: FakeWorkBoardRepository = FakeWorkBoardRepository(),
    source: WorkItem = item,
    workControls: FakeWorkControlRepository = FakeWorkControlRepository(),
  ) = DetailViewModel(source, session, details, board, NoSpecs, NoWebPages, SelectionHolder(), SubtaskSelections(session), clock, workControls, ToastQueue())

  @Test
  fun `the first state holds the row's header before the repository emits`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))

    val state = viewModel().state.value

    assertEquals(item, state.header)
    assertEquals(SectionsUi.Pending, state.sections)
  }

  @Test
  fun `each page request carries the anchor the previous page returned, once`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val viewModel = viewModel()
    advanceUntilIdle()

    details.flow.emit(loaded("a1"))
    advanceUntilIdle()
    viewModel.onAction(DetailAction.LoadMore(DetailList.Subtasks))
    viewModel.onAction(DetailAction.LoadMore(DetailList.Subtasks))
    advanceUntilIdle()
    details.flow.emit(loaded("a2"))
    advanceUntilIdle()
    viewModel.onAction(DetailAction.LoadMore(DetailList.Subtasks))
    advanceUntilIdle()

    val expected = listOf<Triple<String, DetailList, String?>>(
      Triple("SKILL-1", DetailList.Subtasks, "a1"),
      Triple("SKILL-1", DetailList.Subtasks, "a2"),
    )
    assertEquals(expected, details.loads.toList())
  }

  @Test
  fun `latest board running verdict survives a later detail refresh`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val board = FakeWorkBoardRepository()
    val viewModel = viewModel(board)
    advanceUntilIdle()
    details.flow.emit(DetailState(item.copy(running = false), DetailSections.Loaded(emptyDetail, null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()

    board.state.value = loadedBoard(items = listOf(item.copy(running = true)))
    advanceUntilIdle()
    assertTrue(viewModel.state.value.header.running)

    details.flow.emit(DetailState(item.copy(running = false), DetailSections.Loaded(emptyDetail, null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()

    assertTrue(viewModel.state.value.header.running)
  }

  @Test
  fun `resuming a goal on the board refreshes the open detail without navigation`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val board = FakeWorkBoardRepository()
    val viewModel = viewModel(board)
    advanceUntilIdle()
    val idle = item.copy(lifecycle = Lifecycle.Idle, fresh = false, running = false)
    board.state.value = loadedBoard(items = listOf(idle))
    advanceUntilIdle()
    details.flow.emit(DetailState(idle, DetailSections.Loaded(emptyDetail, null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()
    details.refreshes.clear()

    val active = idle.copy(lifecycle = Lifecycle.Active, fresh = true, running = true)
    board.state.value = loadedBoard(items = listOf(active))
    advanceUntilIdle()

    assertEquals(listOf(item.id), details.refreshes)
    details.flow.emit(DetailState(active, DetailSections.Loaded(emptyDetail, null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()
    assertEquals(Lifecycle.Active, viewModel.state.value.header.lifecycle)
    assertTrue(viewModel.state.value.header.fresh)
    assertEquals(null, runActionsView(viewModel.state.value).launch)
  }

  @Test
  fun `resuming a standalone phase opens its new invocation and leaves historical runs alone`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val source = item.copy(
      kind = WorkKind.Phase, lifecycle = Lifecycle.Blocked, standaloneExecution = true,
      control = item.control.copy(invocationId = item.id, phaseId = "plan"),
    )
    val existing = source.copy(id = "previous-plan")
    val board = FakeWorkBoardRepository().apply { state.value = loadedBoard(items = listOf(source, existing)) }
    val viewModel = viewModel(board, source, FakeWorkControlRepository(ControlOutcome.RequestAccepted))
    val effects = mutableListOf<DetailEffect>()
    backgroundScope.launch { viewModel.effects.collect { effects += it } }
    advanceUntilIdle()
    val options = RunOptions(true, true, "Resume", "codex", listOf("codex"), null)
    details.flow.emit(DetailState(source, DetailSections.Loaded(emptyDetail, options, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()
    assertEquals(emptyList(), effects)

    viewModel.onAction(ControlsAction.Launch)
    advanceUntilIdle()
    assertEquals(emptyList(), effects)
    val successor = source.copy(
      id = "new-plan", lifecycle = Lifecycle.Active, running = true,
      control = source.control.copy(invocationId = "new-plan"),
    )
    val otherRepository = successor.copy(id = "other-repo", control = successor.control.copy(repositoryIdentity = "other"))
    board.state.value = loadedBoard(items = listOf(source, existing, otherRepository, successor))
    advanceUntilIdle()

    runCurrent()
    assertEquals(listOf<DetailEffect>(DetailEffect.OpenItem(successor)), effects)
    board.state.value = loadedBoard(items = listOf(source, successor.copy(stepLabel = "next")))
    advanceUntilIdle()
    assertEquals(1, effects.size)
  }

  private fun loaded(anchor: String) = DetailState(
    item,
    DetailSections.Loaded(emptyDetail, null, mapOf(DetailList.Subtasks to anchor), setOf(DetailList.Subtasks), stale = false),
  )

  private class RecordingDetailRepository : WorkDetailRepository {
    val flow = MutableSharedFlow<DetailState>()
    val loads = mutableListOf<Triple<String, DetailList, String?>>()
    val refreshes = mutableListOf<String>()

    override fun observe(item: WorkItem): Flow<DetailState> = flow

    override suspend fun refresh(id: String) { refreshes += id }

    override suspend fun loadList(id: String, list: DetailList, anchor: String?): DetailListOutcome {
      loads += Triple(id, list, anchor)
      return DetailListOutcome.Appended
    }

    override suspend fun prefetch(id: String): RunOptions? = null
  }

  private object NoSpecs : SpecRepository {
    override suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> = error("unused")
    override suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem> = error("unused")
    override fun observeTree(projectId: String): Flow<SpecTreeState> = emptyFlow()
    override fun refreshTree(projectId: String) = Unit
    override fun control(bundle: SpecBundleRef): Flow<SpecControlState> = emptyFlow()
    override suspend fun openFile(path: String) = OpenOutcome.Opened
    override suspend fun openFileInDefaultApp(path: String) = OpenOutcome.NotOpened
    override suspend fun revealFolder(path: String) = OpenOutcome.Opened
  }

  private object NoWebPages : RepositoryWebPages {
    override suspend fun openBranch(repositoryRoot: String, branch: String) = OpenOutcome.Opened
  }
}
