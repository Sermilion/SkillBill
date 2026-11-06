package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.DetailList
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
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecDeleteOutcome
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkChangeScopesRepository
import dev.skillbill.desktop.core.domain.repository.WorkChangesRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
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
  private val activity = object : dev.skillbill.desktop.core.domain.repository.WorkActivityRepository {
    override fun observe(goalWorkflowId: String, running: Boolean) = kotlinx.coroutines.flow.flowOf<dev.skillbill.desktop.core.domain.model.ActivityResult>(dev.skillbill.desktop.core.domain.model.ActivityResult.NotFound)
  }

  private val item = workItem("SKILL-1")
  private val details = RecordingDetailRepository()
  private val changes = RecordingChangesRepository()
  private val scopes = RecordingScopesRepository()
  private val baseline = ChangeBaseline.SubtaskStart("/repo", "base", emptyList())
  private val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) }

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  private val session = object : DatabaseSessionController {
    override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("test.db", 1))
    override fun reopen() = Unit
  }

  private val settings = FakeAppSettingsRepository()

  private fun viewModel(
    board: FakeWorkBoardRepository = FakeWorkBoardRepository(),
    source: WorkItem = item,
    workControls: FakeWorkControlRepository = FakeWorkControlRepository(),
    toastQueue: ToastQueue = ToastQueue(),
    specs: SpecRepository = NoSpecs,
  ) = DetailViewModel(source, session, details, changes, scopes, activity, board, specs, NoWebPages, SelectionHolder(), SubtaskSelections(session), clock, workControls, toastQueue, settings)

  @Test
  fun `opening qa instructions targets the repository file and reports a missing file`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val specs = RecordingSpecs()
    val viewModel = viewModel(specs = specs)
    val effects = mutableListOf<DetailEffect>()
    backgroundScope.launch { viewModel.effects.collect { effects += it } }
    runCurrent()
    details.flow.emit(DetailState(item, DetailSections.Loaded(emptyDetail, null, emptyMap(), emptySet(), stale = false)))
    runCurrent()

    viewModel.onAction(DetailAction.OpenQaPhaseInstructions)
    runCurrent()

    assertEquals(listOf("/work/skill-bill/.skill-bill/phases/qa.md" to "/work/skill-bill"), specs.openedFiles)
    assertEquals(
      DetailEffect.ShowToast(dev.skillbill.desktop.core.ui.feedback.ToastMessage(ToastTone.Problem, "Couldn't open .skill-bill/phases/qa.md. Create it in the repository, then paste the snippet.")),
      effects.single(),
    )
  }

  @Test
  fun `favorite membership follows confirmed settings and a failed write leaves it unchanged`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val database = DatabaseFileIdentity("/selected.db", "file;key")
    val source = item.copy(control = item.control.copy(databaseFile = database))
    val key = FavoriteWorkKey(database, source.id)
    settings.settings.value = settings.settings.value.copy(favoriteWork = setOf(key))
    val toastQueue = ToastQueue()
    val viewModel = viewModel(source = source, toastQueue = toastQueue)
    advanceUntilIdle()
    details.flow.emit(DetailState(source, DetailSections.Loaded(emptyDetail, null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()
    settings.favoriteWriteFailure = true

    viewModel.onAction(DetailAction.ToggleFavorite)
    advanceUntilIdle()

    assertEquals(setOf(key), settings.settings.value.favoriteWork)
    assertTrue(viewModel.state.value.favorite)
    assertEquals("Couldn't save this favorite.", toastQueue.host.toasts.single().data.title)

    settings.favoriteWriteFailure = false
    viewModel.onAction(DetailAction.ToggleFavorite)
    advanceUntilIdle()

    assertTrue(settings.settings.value.favoriteWork.isEmpty())
    assertTrue(!viewModel.state.value.favorite)
  }

  @Test
  fun `a session change hides the old favorite until its detail resolves again`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val database = DatabaseFileIdentity("/selected.db", "file")
    val source = item.copy(control = item.control.copy(databaseFile = database))
    val key = FavoriteWorkKey(database, source.id)
    settings.settings.value = settings.settings.value.copy(favoriteWork = setOf(key))
    val viewModel = viewModel(source = source)
    advanceUntilIdle()
    details.flow.emit(DetailState(source, DetailSections.Loaded(emptyDetail, null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()
    assertTrue(viewModel.state.value.favorite)

    session.state.value = DatabaseSessionState.Open("replacement.db", 2)
    advanceUntilIdle()

    assertTrue(!viewModel.state.value.itemResolved)
    assertTrue(!viewModel.state.value.favorite)
  }

  @Test
  fun `favorite toggle refuses a resolved row without its database observation`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val toastQueue = ToastQueue()
    val viewModel = DetailViewModel(item, session, details, changes, scopes, activity, FakeWorkBoardRepository(), NoSpecs, NoWebPages, SelectionHolder(), SubtaskSelections(session), clock, FakeWorkControlRepository(), toastQueue, settings)
    advanceUntilIdle()
    details.flow.emit(DetailState(item, DetailSections.Loaded(emptyDetail, null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()

    viewModel.onAction(DetailAction.ToggleFavorite)

    assertTrue(settings.settings.value.favoriteWork.isEmpty())
    assertEquals("This work item has no database identity, so it cannot be favorited.", toastQueue.host.toasts.single().data.title)
  }

  @Test
  fun `the first state holds the row's header before the repository emits`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))

    val state = viewModel().state.value

    assertEquals(item, state.header)
    assertEquals(SectionsUi.Pending, state.sections)
  }

  @Test
  fun `a report-only standalone review never reads or opens a branch-wide changes summary`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val review = item.copy(
      kind = WorkKind.Phase, standaloneExecution = true,
      control = item.control.copy(invocationId = "review-1", phaseId = "review"),
    )
    val viewModel = viewModel(source = review)
    val effects = mutableListOf<DetailEffect>()
    backgroundScope.launch { viewModel.effects.collect { effects += it } }
    advanceUntilIdle()
    details.flow.emit(DetailState(review, DetailSections.Loaded(emptyDetail.copy(changeBaseline = baseline), null, emptyMap(), emptySet(), stale = false)))
    advanceUntilIdle()
    viewModel.onAction(DetailAction.OpenChangesWindow())
    runCurrent()
    assertEquals(emptyList(), changes.observed)
    assertEquals(emptyList(), effects)
    assertTrue(!viewModel.state.value.header.showsChanges)
    assertTrue(!review.copy(control = review.control.copy(phaseId = "plan")).showsChanges)
    assertTrue(!review.copy(control = review.control.copy(phaseId = "implement")).showsChanges)
    assertTrue(review.copy(kind = WorkKind.Goal, standaloneExecution = false).showsChanges)
  }

  @Test
  fun `a goal with no running subtask shows its strip and opens the window on all changes or a selectable subtask`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val viewModel = viewModel()
    val effects = mutableListOf<DetailEffect>()
    backgroundScope.launch { viewModel.effects.collect { effects += it } }
    advanceUntilIdle()
    details.flow.emit(loaded("a"))
    advanceUntilIdle()
    val done = ScopeSubtask(12, "Twelve", ScopeSubtaskState.DONE, "e12", listOf("e12"), false, emptyList())
    val notStarted = ScopeSubtask(13, "Thirteen", ScopeSubtaskState.NOT_STARTED, null, emptyList(), false, emptyList())
    val listed = SubtaskScopesState.Listed("/repo", "main", rangesSupported = true, subtasks = listOf(done, notStarted), stats = emptyMap())
    scopes.flow.value = listed
    advanceUntilIdle()
    assertEquals(listed, viewModel.state.value.changes.subtasks)

    viewModel.onAction(DetailAction.OpenChangesWindow())
    viewModel.onAction(DetailAction.OpenChangesWindow(ChangeScope.Subtasks(12, 12)))
    viewModel.onAction(DetailAction.OpenChangesWindow(ChangeScope.Subtasks(13, 13)))
    runCurrent()

    val expected = listOf<DetailEffect>(
      DetailEffect.OpenChangesWindow(item, ChangeScope.All),
      DetailEffect.OpenChangesWindow(item, ChangeScope.Subtasks(12, 12)),
    )
    assertEquals(expected, effects)
    assertEquals(emptyList(), changes.observed)
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
    val read = DetailState(source, DetailSections.Loaded(emptyDetail.copy(selectedExecution = execution), null, emptyMap(), emptySet(), stale = false))
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
  fun `validation check toggle resolves selected execution outside history page`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val source = item.copy(kind = WorkKind.Phase, control = item.control.copy(invocationId = "older-run"))
    val check = dev.skillbill.desktop.core.domain.model.ValidationCheck(
      "compile", "Compile", "./gradlew check", ".", listOf(
        dev.skillbill.desktop.core.domain.model.ValidationCheckAttempt(1, epoch, epoch, dev.skillbill.desktop.core.domain.model.ValidationCheckResult.Failed, 1, null),
        dev.skillbill.desktop.core.domain.model.ValidationCheckAttempt(2, epoch, epoch, dev.skillbill.desktop.core.domain.model.ValidationCheckResult.Passed, 0, null),
      ),
    )
    val execution = dev.skillbill.desktop.core.domain.model.ExecutionInstance(
      "older-run", "older-run", "completed", epoch, epoch, epoch, null, null, null, null, null,
      liveChecks = true, validationChecks = listOf(check),
    )
    val viewModel = viewModel(source = source)
    advanceUntilIdle()
    details.flow.emit(DetailState(
      source,
      DetailSections.Loaded(emptyDetail.copy(selectedExecution = execution), null, emptyMap(), emptySet(), stale = false),
    ))
    advanceUntilIdle()

    val key = ValidationCheckKey("older-run", "compile")
    viewModel.onAction(DetailAction.ToggleValidationCheck(key))

    assertEquals(true, viewModel.state.value.validationCheckOverrides[key])
  }

  @Test
  fun `a phase item that goes idle keeps changes hidden and never reads subtask scopes`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = item.copy(kind = WorkKind.Phase)
    val board = FakeWorkBoardRepository()
    val viewModel = viewModel(board, phase)
    advanceUntilIdle()
    val loaded = DetailSections.Loaded(emptyDetail.copy(changeBaseline = baseline), null, emptyMap(), emptySet(), stale = false)
    board.state.value = loadedBoard(items = listOf(phase.copy(running = true)))
    details.flow.emit(DetailState(phase, loaded))
    advanceUntilIdle()

    board.state.value = loadedBoard(items = listOf(phase.copy(running = false)))
    advanceUntilIdle()
    assertEquals(emptyList(), changes.observed)
    assertTrue(!viewModel.state.value.header.showsChanges)
    assertEquals(0, scopes.subtaskReads)
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

    override suspend fun resolve(id: String): dev.skillbill.desktop.core.domain.repository.WorkDetailResolution =
      dev.skillbill.desktop.core.domain.repository.WorkDetailResolution.Missing

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

    override fun observe(baseline: ChangeBaseline?, running: Boolean): Flow<ChangesState> {
      observed += baseline to running
      return flow
    }

    override suspend fun fileDiff(baseline: ChangeBaseline, baseSha: String, file: ChangedFile): FileDiffState = error("unused")

    override suspend fun open(baseline: ChangeBaseline, file: ChangedFile): OpenOutcome = error("unused")
  }

  private class RecordingScopesRepository : WorkChangeScopesRepository {
    val flow = MutableStateFlow<SubtaskScopesState>(SubtaskScopesState.Loading)
    var subtaskReads = 0

    override fun observeSubtasks(goalWorkflowId: String, running: Boolean): Flow<SubtaskScopesState> {
      subtaskReads++
      return flow
    }

    override fun observeCommitStats(target: ChangeScopeTarget): Flow<Map<String, dev.skillbill.desktop.core.domain.model.SubtaskStats>> = emptyFlow()

    override fun observeScope(target: ChangeScopeTarget, scope: ChangeScope, running: Boolean): Flow<ScopeChangesState> = emptyFlow()

    override suspend fun fileDiff(target: ChangeScopeTarget, scope: ChangeScope, file: ChangedFile): FileDiffState = error("unused")
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

  private class RecordingSpecs : SpecRepository by NoSpecs {
    val openedFiles = mutableListOf<Pair<String, String>>()

    override suspend fun openFileUnder(path: String, directory: String): OpenOutcome {
      openedFiles += path to directory
      return OpenOutcome.NotOpened
    }
  }

  private object NoWebPages : RepositoryWebPages {
    override suspend fun openBranch(repositoryRoot: String, branch: String) = OpenOutcome.Opened
  }
}
