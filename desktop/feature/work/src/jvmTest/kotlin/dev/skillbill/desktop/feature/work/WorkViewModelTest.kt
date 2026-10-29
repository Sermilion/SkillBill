package dev.skillbill.desktop.feature.work

import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.testing.ControlCall
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
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
    val viewModel = WorkViewModel(board, FakeWorkDetailRepository(), controls, SelectionHolder(), clock)
    advanceUntilIdle()

    viewModel.onAction(WorkAction.MoveFocus(1))
    viewModel.onAction(WorkAction.MoveFocus(1))
    viewModel.onAction(WorkAction.FocusedKey(RowKey.Pause))
    advanceUntilIdle()

    assertEquals(listOf(ControlCall("pauseAfterSubtask", goalB)), controls.calls)
    val expected = controlFeedback(ControlAction.PauseAfterSubtask, ControlOutcome.IntentRecorded, FeedbackSubject("SKILL-2", null, null))
    assertEquals(WorkEffect.ShowToast(expected), viewModel.effects.first())
  }
}
