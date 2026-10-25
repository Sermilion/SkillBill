package dev.skillbill.tray.feature.terminal

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeWorkDetailRepository
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val details = FakeWorkDetailRepository()
  private val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings()))

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `toggling persists the open flag and the state follows it`() = runTest(dispatcher) {
    val viewModel = TerminalViewModel(details, settings)
    runCurrent()
    assertFalse(viewModel.state.value.paneOpen)

    viewModel.onAction(TerminalAction.TogglePane)
    runCurrent()

    assertTrue(settings.updates.single().terminalPaneOpen)
    assertTrue(viewModel.state.value.paneOpen)
  }

  @Test
  fun `an oversized resize persists the maximum height instead of an invalid settings value`() = runTest(dispatcher) {
    val viewModel = TerminalViewModel(details, settings)
    runCurrent()

    viewModel.onAction(TerminalAction.ResizeTo(5000))
    runCurrent()

    assertEquals(AppSettings.TERMINAL_PANE_MAX_HEIGHT_DP, settings.updates.single().terminalPaneHeightDp)
    assertEquals(AppSettings.TERMINAL_PANE_MAX_HEIGHT_DP, viewModel.state.value.heightDp)
  }

  @Test
  fun `the working directory is the selected work's repository root and clears when the read fails`() = runTest(dispatcher) {
    val item = workItem("a")
    val viewModel = TerminalViewModel(details, settings)
    details.onDetail = { Outcome.Value(detail(item)) }

    viewModel.onAction(TerminalAction.SelectedWorkChanged(item.identity))
    runCurrent()
    assertEquals("/repo/a", viewModel.state.value.workingDirectory)

    details.onDetail = { Outcome.Failure(Problem(ProblemCode.UNKNOWN, "gone")) }
    viewModel.onAction(TerminalAction.SelectedWorkChanged(item.identity))
    runCurrent()
    assertNull(viewModel.state.value.workingDirectory)
  }

  @Test
  fun `a slow read for an earlier selection never overwrites the newer selection's directory`() = runTest(dispatcher) {
    val older = workItem("a")
    val newer = workItem("b")
    val olderRead = CompletableDeferred<Unit>()
    val viewModel = TerminalViewModel(details, settings)
    details.onDetail = { call ->
      if (call.identity == older.identity) olderRead.await()
      Outcome.Value(detail(if (call.identity == older.identity) older else newer))
    }

    viewModel.onAction(TerminalAction.SelectedWorkChanged(older.identity))
    runCurrent()
    viewModel.onAction(TerminalAction.SelectedWorkChanged(newer.identity))
    runCurrent()
    olderRead.complete(Unit)
    runCurrent()

    assertEquals("/repo/b", viewModel.state.value.workingDirectory)
  }
}
