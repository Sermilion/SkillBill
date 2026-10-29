package dev.skillbill.desktop.feature.history

import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class HistoryViewModelTest {
  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun `the Goals tab keeps only finished goals in runtime order`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val finished = Instant.fromEpochMilliseconds(1_000)
    val g2 = workItem("G2", finishedAt = finished)
    val p1 = workItem("P1", kind = WorkKind.Phase, finishedAt = finished)
    val g3 = workItem("G3")
    val g1 = workItem("G1", finishedAt = finished)
    val board = FakeWorkBoardRepository(loadedBoard(items = listOf(g2, p1, g3, g1)))
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(historyWindow = HistoryWindow.Day))
    val clock = object : Clock { override fun now() = finished }
    val viewModel = HistoryViewModel(board, settings, SelectionHolder(), clock)
    advanceUntilIdle()

    viewModel.onAction(HistoryAction.ChooseTab(HistoryTab.Goals))

    assertEquals(listOf(g2, g1), viewModel.state.value.rows)
    assertEquals(HistoryWindow.Day, board.queries.last().window)
  }
}
