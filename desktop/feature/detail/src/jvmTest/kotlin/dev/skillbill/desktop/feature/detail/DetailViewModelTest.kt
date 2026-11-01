package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
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
import dev.skillbill.desktop.core.domain.model.SpecDeleteOutcome
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkChangesRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
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
  private val changes = RecordingChangesRepository()
  private val baseline = ChangeBaseline.SubtaskStart("/repo", "base", emptyList())
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
  ) = DetailViewModel(source, session, details, changes, board, NoSpecs, NoWebPages, SelectionHolder(), SubtaskSelections(session), clock, workControls, ToastQueue())

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

  @Test
  fun `QA case overrides and selected run survive a refreshed detail`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val source = item.copy(kind = WorkKind.Phase, control = item.control.copy(invocationId = "run-1"))
    val checklist = dev.skillbill.desktop.core.domain.model.QaChecklist(
      null, null, null, null, emptyList(), null,
      listOf(dev.skillbill.desktop.core.domain.model.QaChecklistCase("C1", "Case", listOf(
        dev.skillbill.desktop.core.domain.model.QaChecklistStep("run", dev.skillbill.desktop.core.domain.model.QaStepState.Running, null, null, emptyList(), epoch),
      ))), emptyList(), epoch,
    )
    val execution = dev.skillbill.desktop.core.domain.model.ExecutionInstance(
      "run-1", "run-1", "active", epoch, epoch, null, null, null, null, null, null, null, checklist,
    )
    val read = DetailState(source, DetailSections.Loaded(emptyDetail.copy(executionHistory = listOf(execution)), null, emptyMap(), emptySet(), stale = false))
    val viewModel = viewModel(source = source)
    val effects = mutableListOf<DetailEffect>()
    backgroundScope.launch { viewModel.effects.collect { effects += it } }
    advanceUntilIdle()
    details.flow.emit(read)
    advanceUntilIdle()
    val key = QaCaseKey("run-1", "C1")
    viewModel.onAction(DetailAction.ToggleQaCase(key))
    viewModel.onAction(DetailAction.SelectQaRun("run-1"))
    viewModel.onAction(DetailAction.SelectQaRun(null))
    viewModel.onAction(DetailAction.OpenQaEvidence("/repo/run-1/evidence.png", "/repo/run-1"))
    details.flow.emit(read)
    advanceUntilIdle()

    assertEquals(false, viewModel.state.value.qaCaseOverrides[key])
    assertEquals("run-1", viewModel.state.value.selectedQaRun)
    assertEquals(listOf(ToastTone.Problem), effects.filterIsInstance<DetailEffect.ShowToast>().map { it.message.tone })
  }

  @Test
  fun `an open diff follows the refreshed list and only a listed file still on disk is opened`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val viewModel = viewModel()
    val effects = mutableListOf<DetailEffect>()
    backgroundScope.launch { viewModel.effects.collect { effects += it } }
    advanceUntilIdle()
    details.flow.emit(DetailState(item, DetailSections.Loaded(emptyDetail.copy(changeBaseline = baseline), null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()
    val modified = ChangedFile("src/A.kt", null, ChangeStatus.Modified, 4, 2)
    val deleted = ChangedFile("src/B.kt", null, ChangeStatus.Deleted, 0, 9)
    changes.flow.emit(ChangesState.Listed(ChangeSet("base", listOf(modified, deleted), 4, 11)))
    advanceUntilIdle()

    changes.diffs += FileDiffState.Shown(FileDiff(emptyList(), 4, 2))
    viewModel.onAction(DetailAction.SelectChangedFile(modified))
    advanceUntilIdle()
    assertEquals(DiffContent.Read(FileDiffState.Shown(FileDiff(emptyList(), 4, 2))), viewModel.state.value.diff?.content)

    val grown = modified.copy(insertions = 6)
    val recreated = ChangedFile(deleted.path, null, ChangeStatus.Untracked, 3, 0)
    changes.diffs += FileDiffState.Shown(FileDiff(emptyList(), 6, 2))
    changes.flow.emit(ChangesState.Listed(ChangeSet("base", listOf(grown, deleted, recreated), 9, 11)))
    advanceUntilIdle()
    assertEquals(listOf(modified, grown), changes.diffReads)
    assertEquals(DiffContent.Read(FileDiffState.Shown(FileDiff(emptyList(), 6, 2))), viewModel.state.value.diff?.content)

    viewModel.onAction(DetailAction.OpenChangedFile(deleted))
    viewModel.onAction(DetailAction.OpenChangedFile(recreated))
    viewModel.onAction(DetailAction.OpenChangedFile(grown))
    advanceUntilIdle()
    runCurrent()
    assertEquals(listOf(recreated, grown), changes.opens)
    val toasts = effects.filterIsInstance<DetailEffect.ShowToast>()
    assertEquals(listOf(ToastTone.Problem, ToastTone.Problem), toasts.map { it.message.tone })
  }

  @Test
  fun `an item that goes idle is observed again without polling`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val board = FakeWorkBoardRepository()
    val viewModel = viewModel(board)
    advanceUntilIdle()
    val loaded = DetailSections.Loaded(emptyDetail.copy(changeBaseline = baseline), null, emptyMap(), emptySet(), stale = false)
    board.state.value = loadedBoard(items = listOf(item.copy(running = true)))
    details.flow.emit(DetailState(item, loaded))
    advanceUntilIdle()

    board.state.value = loadedBoard(items = listOf(item.copy(running = false)))
    advanceUntilIdle()
    val listed = ChangesState.Listed(ChangeSet("base", emptyList(), 0, 0))
    changes.flow.emit(listed)
    advanceUntilIdle()

    assertEquals(listOf<Pair<ChangeBaseline?, Boolean>>(baseline to true, baseline to false), changes.observed)
    assertEquals(listed, viewModel.state.value.changes.state)
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

  private class RecordingChangesRepository : WorkChangesRepository {
    val flow = MutableSharedFlow<ChangesState>()
    val observed = mutableListOf<Pair<ChangeBaseline?, Boolean>>()
    val diffs = ArrayDeque<FileDiffState>()
    val diffReads = mutableListOf<ChangedFile>()
    val opens = mutableListOf<ChangedFile>()

    override fun observe(baseline: ChangeBaseline?, running: Boolean): Flow<ChangesState> {
      observed += baseline to running
      return flow
    }

    override suspend fun fileDiff(worktreeRoot: String, baseSha: String, file: ChangedFile): FileDiffState {
      diffReads += file
      return diffs.removeFirst()
    }

    override suspend fun open(worktreeRoot: String, file: ChangedFile): OpenOutcome {
      opens += file
      return OpenOutcome.NotOpened
    }
  }

  private object NoSpecs : SpecRepository {
    override suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> = error("unused")
    override suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem> = error("unused")
    override fun observeTree(projectId: String): Flow<SpecTreeState> = emptyFlow()
    override fun refreshTree(projectId: String) = Unit
    override fun control(bundle: SpecBundleRef): Flow<SpecControlState> = emptyFlow()
    override suspend fun openFile(path: String) = OpenOutcome.Opened
    override suspend fun openFileInDefaultApp(path: String) = OpenOutcome.NotOpened
    override suspend fun openFileUnder(path: String, directory: String) = OpenOutcome.NotOpened
    override suspend fun revealFolder(path: String) = OpenOutcome.Opened
    override suspend fun deleteBundle(bundle: SpecBundleRef): SpecDeleteOutcome = error("unused")
  }

  private object NoWebPages : RepositoryWebPages {
    override suspend fun openBranch(repositoryRoot: String, branch: String) = OpenOutcome.Opened
  }
}
