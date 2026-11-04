package dev.skillbill.desktop.feature.work

import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ExportPreflightOutcome
import dev.skillbill.desktop.core.domain.model.ImportOutcome
import dev.skillbill.desktop.core.domain.model.ArchiveInspectOutcome
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
      exportPreflightOutcome = ExportPreflightOutcome.Refused("Goal is live; export refused.")
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
    )
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
    val board = FakeWorkBoardRepository(loadedBoard())
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
    )
    viewModel.onAction(WorkAction.RequestImport)
    advanceUntilIdle()
    viewModel.onAction(WorkAction.ConfirmImport)
    advanceUntilIdle()

    assertEquals(listOf("/tmp/SKILL-4.zip"), controls.inspectRequests)
    assertEquals(listOf("/tmp/SKILL-4.zip"), controls.importRequests)
    assertEquals(1, board.refreshes)
  }
}
