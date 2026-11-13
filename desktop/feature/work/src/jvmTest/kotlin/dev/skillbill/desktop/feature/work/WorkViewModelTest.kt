package dev.skillbill.desktop.feature.work

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ExportOutcome
import dev.skillbill.desktop.core.domain.model.ExportPreflightOutcome
import dev.skillbill.desktop.core.domain.model.ExportRefusalCategory
import dev.skillbill.desktop.core.domain.model.ArchiveInspectOutcome
import dev.skillbill.desktop.core.domain.model.ImportOutcome
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecDeleteOutcome
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.ControlCall
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeArchiveFileChooser
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ControlAction
import dev.skillbill.desktop.core.ui.feedback.FeedbackSubject
import dev.skillbill.desktop.core.ui.feedback.controlFeedback
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

private object TestSession : DatabaseSessionController {
  override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("test", 1L))
  override fun reopen() = Unit
}

private object EmptySpecRepository : SpecRepository {
  override suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> = Outcome.Success(emptyList())
  override suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem> = error("unused")
  override fun observeTree(projectId: String): Flow<SpecTreeState> = error("unused")
  override fun refreshTree(projectId: String) = error("unused")
  override fun control(bundle: SpecBundleRef): Flow<SpecControlState> = error("unused")
  override suspend fun openFile(path: String): OpenOutcome = error("unused")
  override suspend fun openFileInDefaultApp(path: String): OpenOutcome = error("unused")
  override suspend fun openFileUnder(path: String, directory: String): OpenOutcome = error("unused")
  override suspend fun revealFolder(path: String): OpenOutcome = error("unused")
  override suspend fun deleteBundle(bundle: SpecBundleRef): SpecDeleteOutcome = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkViewModelTest {
  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun `a key on the focused row runs that row's control and toasts the outcome's copy`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val goalA = workItem("SKILL-1")
    val goalB = workItem("SKILL-2")
    val board = FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(goalA, goalB))))
    val controls = FakeWorkControlRepository(ControlOutcome.IntentRecorded)
    val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) }
    val viewModel = WorkViewModel(
      board,
      FakeWorkDetailRepository(),
      controls,
      SelectionHolder(),
      clock,
      FakeArchiveFileChooser(),
      TestSession,
      EmptySpecRepository,
    )
    advanceUntilIdle()

    viewModel.onAction(WorkAction.MoveFocus(1))
    viewModel.onAction(WorkAction.MoveFocus(1))
    viewModel.onAction(WorkAction.FocusedKey(RowKey.Pause))
    advanceUntilIdle()

    assertEquals(listOf(ControlCall("pauseAfterSubtask", goalB)), controls.calls)
    val expected = controlFeedback(ControlAction.PauseAfterSubtask, ControlOutcome.IntentRecorded, FeedbackSubject("SKILL-2", null, null))
    assertEquals(WorkEffect.ShowToast(expected), viewModel.effects.first())
  }

  @Test
  fun `launch opens detail when the recorded agent is unavailable and launches supported agents directly`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val unresolved = workItem("SONA-19-unresolved")
    val supported = workItem("SONA-19-supported")
    val board = FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(unresolved, supported))))
    val details = FakeWorkDetailRepository(
      mapOf(
        unresolved.id to RunOptions(false, true, "Resume", null, listOf("claude", "codex"), null),
        supported.id to RunOptions(false, true, "Resume", "codex", listOf("claude", "codex"), null),
      ),
    )
    val controls = FakeWorkControlRepository()
    val viewModel = WorkViewModel(
      board,
      details,
      controls,
      SelectionHolder(),
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      FakeArchiveFileChooser(),
      TestSession,
      EmptySpecRepository,
    )
    advanceUntilIdle()
    viewModel.onAction(WorkAction.Focus(unresolved))
    advanceUntilIdle()

    viewModel.onAction(WorkAction.Launch(unresolved))
    assertEquals(WorkEffect.OpenDetail(unresolved), viewModel.effects.first())
    assertEquals(emptyList(), controls.calls)

    viewModel.onAction(WorkAction.Focus(supported))
    advanceUntilIdle()
    viewModel.onAction(WorkAction.FocusedKey(RowKey.Launch))
    advanceUntilIdle()

    assertEquals(listOf(ControlCall("launch", supported, "codex")), controls.calls)
  }

  @Test
  fun `a selected row follows refreshed policy and clears when the row leaves the board`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val original = workItem("SKILL-1")
    val board = FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(original))))
    val selection = SelectionHolder()
    val viewModel = WorkViewModel(
      board, FakeWorkDetailRepository(), FakeWorkControlRepository(ControlOutcome.IntentRecorded), selection,
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      FakeArchiveFileChooser(),
      TestSession,
      EmptySpecRepository,
    )
    advanceUntilIdle()
    viewModel.onAction(WorkAction.Focus(original))
    advanceUntilIdle()

    val refreshed = original.copy(pauseEligible = false)
    board.state.value = loadedBoard(mapOf(BoardSectionKind.Goals to listOf(refreshed)))
    advanceUntilIdle()
    assertEquals(refreshed, selection.item.value)

    board.state.value = loadedBoard()
    advanceUntilIdle()
    assertEquals(null, selection.item.value)
  }

  @Test
  fun `a live goal export stays blocked after pause after subtask until coordinator reports idle`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val item = workItem("SKILL-3")
    val board = FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(item))))
    val controls = FakeWorkControlRepository().apply {
      exportPreflightOutcome = ExportPreflightOutcome.Refused(
        "Goal is live; export refused.",
        ExportRefusalCategory.LIVENESS,
      )
    }
    val chooser = FakeArchiveFileChooser().apply { nextSave = "/tmp/SKILL-3.zip" }
    val viewModel = WorkViewModel(
      board,
      FakeWorkDetailRepository(),
      controls,
      SelectionHolder(),
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      chooser,
      TestSession,
      EmptySpecRepository,
    )
    viewModel.onAction(WorkAction.RequestExport(item))
    advanceUntilIdle()
    viewModel.onAction(WorkAction.PauseAfterSubtask(item))
    viewModel.onAction(WorkAction.RequestExport(item))
    advanceUntilIdle()

    assertEquals(2, controls.preflightRequests.size)
    assertEquals(listOf(ControlCall("pauseAfterSubtask", item)), controls.calls)
    assertEquals(emptyList(), chooser.saveNames)
    assertEquals(emptyList(), controls.exportRequests)
    assertEquals(item, viewModel.state.value.exportLiveTarget)
  }

  @Test
  fun `branch refusals with live in the branch name show the reason at preflight and export`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val item = workItem("SKILL-474")
    val controls = FakeWorkControlRepository().apply {
      exportPreflightOutcome = ExportPreflightOutcome.Refused(
        "Current branch 'main' is not the recorded feature branch 'feature/delivery'; export refused.",
        ExportRefusalCategory.BRANCH,
      )
    }
    val chooser = FakeArchiveFileChooser().apply { nextSave = "/tmp/SKILL-474.zip" }
    val viewModel = WorkViewModel(
      FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(item)))),
      FakeWorkDetailRepository(),
      controls,
      SelectionHolder(),
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      chooser,
      TestSession,
      EmptySpecRepository,
    )

    viewModel.onAction(WorkAction.RequestExport(item))
    advanceUntilIdle()
    val preflightEffect = viewModel.effects.first()
    assertEquals("Current branch 'main' is not the recorded feature branch 'feature/delivery'; export refused.", (preflightEffect as WorkEffect.ShowToast).message.text)
    assertEquals(null, viewModel.state.value.exportLiveTarget)

    controls.exportPreflightOutcome = ExportPreflightOutcome.Ready("feature/delivery", "abc123")
    controls.exportOutcome = ExportOutcome.Refused(
      "Current branch 'main' is not the recorded feature branch 'feature/delivery'; export refused.",
      ExportRefusalCategory.BRANCH,
    )
    viewModel.onAction(WorkAction.RequestExport(item))
    advanceUntilIdle()
    val exportEffect = viewModel.effects.first()
    assertEquals("Current branch 'main' is not the recorded feature branch 'feature/delivery'; export refused.", (exportEffect as WorkEffect.ShowToast).message.text)
    assertEquals(null, viewModel.state.value.exportLiveTarget)
  }

  @Test
  fun `canceling archive selection does not inspect or import`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val board = FakeWorkBoardRepository(loadedBoard())
    val controls = FakeWorkControlRepository()
    val chooser = FakeArchiveFileChooser()
    val viewModel = WorkViewModel(
      board,
      FakeWorkDetailRepository(),
      controls,
      SelectionHolder(),
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      chooser,
      TestSession,
      EmptySpecRepository,
    )
    advanceUntilIdle()
    viewModel.onAction(WorkAction.RequestImport)
    advanceUntilIdle()

    assertEquals(1, chooser.openRequests)
    assertEquals(emptyList(), controls.inspectRequests)
    assertEquals(emptyList(), controls.importRequests)
  }

  @Test
  fun `an idle export sends the selected save path to the repository`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val item = workItem("SKILL-5")
    val board = FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(item))))
    val controls = FakeWorkControlRepository().apply {
      exportPreflightOutcome = ExportPreflightOutcome.Ready("feature/SKILL-5", "abc123")
    }
    val chooser = FakeArchiveFileChooser().apply { nextSave = "/tmp/SKILL-5-archive.zip" }
    val viewModel = WorkViewModel(
      board,
      FakeWorkDetailRepository(),
      controls,
      SelectionHolder(),
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      chooser,
      TestSession,
      EmptySpecRepository,
    )
    viewModel.onAction(WorkAction.RequestExport(item))
    advanceUntilIdle()

    assertEquals(listOf("SKILL-5-archive.zip"), chooser.saveNames)
    assertEquals(
      listOf<Triple<String, String?, String>>(Triple("SKILL-5", item.repositoryRoot, "/tmp/SKILL-5-archive.zip")),
      controls.exportRequests,
    )
  }

  @Test
  fun `a confirmed import refreshes the board observation`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val item = workItem("SKILL-4")
    val board = FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(item))))
    val controls = FakeWorkControlRepository().apply {
      archiveInspectOutcome = ArchiveInspectOutcome.Ready("SKILL-4", "feature/SKILL-4", "abc123", "fingerprint")
      importOutcome = ImportOutcome.Imported("SKILL-4", "feature/SKILL-4", "abc123")
    }
    val chooser = FakeArchiveFileChooser().apply { nextOpen = "/tmp/SKILL-4.zip" }
    val viewModel = WorkViewModel(
      board,
      FakeWorkDetailRepository(),
      controls,
      SelectionHolder(),
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      chooser,
      TestSession,
      EmptySpecRepository,
    )
    advanceUntilIdle()
    viewModel.onAction(WorkAction.RequestImport)
    advanceUntilIdle()
    viewModel.onAction(WorkAction.ConfirmImport)
    advanceUntilIdle()

    assertEquals(listOf("/tmp/SKILL-4.zip"), controls.inspectRequests)
    assertEquals(listOf("/tmp/SKILL-4.zip"), controls.importRequests)
    assertEquals(listOf<Triple<String, String?, String>>(Triple("/tmp/SKILL-4.zip", item.repositoryRoot, "fingerprint")), controls.importDetails)
    assertEquals(1, board.refreshes)
  }

  @Test
  fun `dismissing an inspected archive confirmation does not import`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val controls = FakeWorkControlRepository().apply {
      archiveInspectOutcome = ArchiveInspectOutcome.Ready("SKILL-4", "feature/SKILL-4", "abc123", "fingerprint")
    }
    val chooser = FakeArchiveFileChooser().apply { nextOpen = "/tmp/SKILL-4.zip" }
    val viewModel = WorkViewModel(
      FakeWorkBoardRepository(loadedBoard()),
      FakeWorkDetailRepository(),
      controls,
      SelectionHolder(),
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      chooser,
      TestSession,
      EmptySpecRepository,
    )
    advanceUntilIdle()

    viewModel.onAction(WorkAction.RequestImport)
    advanceUntilIdle()
    assertEquals(listOf("/tmp/SKILL-4.zip"), controls.inspectRequests)
    assertEquals("SKILL-4", viewModel.state.value.importDialog?.issueKey)

    viewModel.onAction(WorkAction.DismissImport)
    advanceUntilIdle()

    assertEquals(null, viewModel.state.value.importDialog)
    assertEquals(emptyList(), controls.importRequests)
  }

  @Test
  fun `an incomplete import retry keeps its repository archive fingerprint and session`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val item = workItem("SKILL-6").copy(repositoryRoot = "/work/other-checkout", repositoryName = "other-checkout")
    val board = FakeWorkBoardRepository(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(item))))
    val controls = FakeWorkControlRepository().apply {
      archiveInspectOutcome = ArchiveInspectOutcome.Ready("SKILL-6", "feature/SKILL-6", "abc123", "fingerprint")
      importOutcome = ImportOutcome.Incomplete(listOf("SKILL-6.md"))
    }
    val chooser = FakeArchiveFileChooser().apply { nextOpen = "/tmp/SKILL-6.zip" }
    val viewModel = WorkViewModel(
      board,
      FakeWorkDetailRepository(),
      controls,
      SelectionHolder(),
      object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) },
      chooser,
      TestSession,
      EmptySpecRepository,
    )
    advanceUntilIdle()
    viewModel.onAction(WorkAction.RequestImport)
    advanceUntilIdle()
    viewModel.onAction(WorkAction.ConfirmImport)
    advanceUntilIdle()

    assertEquals(true, viewModel.state.value.importDialog?.retryPending)
    controls.importOutcome = ImportOutcome.Imported("SKILL-6", "feature/SKILL-6", "abc123")
    viewModel.onAction(WorkAction.ConfirmImport)
    advanceUntilIdle()

    assertEquals(
      listOf<Triple<String, String?, String>>(
        Triple("/tmp/SKILL-6.zip", "/work/other-checkout", "fingerprint"),
        Triple("/tmp/SKILL-6.zip", "/work/other-checkout", "fingerprint"),
      ),
      controls.importDetails,
    )
    assertEquals(null, viewModel.state.value.importDialog)
  }
}
