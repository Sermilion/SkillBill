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
  fun `the directory is not resolved for a selection until its read finishes, and a failed read still resolves it`() = runTest(dispatcher) {
    val item = workItem("a")
    val read = CompletableDeferred<Unit>()
    val viewModel = TerminalViewModel(details, settings)
    details.onDetail = {
      read.await()
      Outcome.Failure(Problem(ProblemCode.UNKNOWN, "gone"))
    }

    viewModel.onAction(TerminalAction.SelectedWorkChanged(item.identity))
    runCurrent()
    assertNull(viewModel.state.value.resolvedFor)

    read.complete(Unit)
    runCurrent()
    assertEquals(item.identity, viewModel.state.value.resolvedFor)
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

  @Test
  fun `a spec picked after a sidebar item moves the directory to the spec's project and keeps the sidebar selection settled`() = runTest(dispatcher) {
    val item = workItem("a")
    val viewModel = TerminalViewModel(details, settings)
    details.onDetail = { Outcome.Value(detail(item)) }
    viewModel.onAction(TerminalAction.SelectedWorkChanged(item.identity))
    runCurrent()

    viewModel.onAction(TerminalAction.SpecSelected("/repo/specs-project"))
    runCurrent()

    assertEquals("/repo/specs-project", viewModel.state.value.workingDirectory)
    assertEquals(item.identity, viewModel.state.value.resolvedFor)
  }

  @Test
  fun `a spec picked while a sidebar read is in flight wins over that read and settles the selection`() = runTest(dispatcher) {
    val item = workItem("a")
    val read = CompletableDeferred<Unit>()
    val viewModel = TerminalViewModel(details, settings)
    details.onDetail = {
      read.await()
      Outcome.Value(detail(item))
    }

    viewModel.onAction(TerminalAction.SelectedWorkChanged(item.identity))
    runCurrent()
    viewModel.onAction(TerminalAction.SpecSelected(null))
    read.complete(Unit)
    runCurrent()

    assertNull(viewModel.state.value.workingDirectory)
    assertEquals(item.identity, viewModel.state.value.resolvedFor)
  }

  @Test
  fun `a sidebar item picked after a spec moves the directory back to the item's project`() = runTest(dispatcher) {
    val item = workItem("a")
    val viewModel = TerminalViewModel(details, settings)
    details.onDetail = { Outcome.Value(detail(item)) }

    viewModel.onAction(TerminalAction.SpecSelected("/repo/specs-project"))
    viewModel.onAction(TerminalAction.SelectedWorkChanged(item.identity))
    runCurrent()

    assertEquals("/repo/a", viewModel.state.value.workingDirectory)
  }

  private fun TerminalViewModel.openTabs(vararg directories: String) {
    directories.forEachIndexed { index, directory -> onAction(TerminalAction.NewTab(index + 1L, directory, isHome = false)) }
  }

  @Test
  fun `an opening activates the most recently used tab of its directory once and creates one only when none matches`() = runTest(dispatcher) {
    val viewModel = TerminalViewModel(details, settings)
    runCurrent()
    viewModel.openTabs("/repo/a", "/repo/b", "/repo/a")
    viewModel.onAction(TerminalAction.SelectTab(2))
    viewModel.onAction(TerminalAction.SelectTab(99))
    runCurrent()
    assertEquals(2L, viewModel.state.value.activeTabId)
    assertEquals(listOf("a 1", "b", "a 2"), viewModel.state.value.tabs.map { it.label })

    viewModel.onAction(TerminalAction.TogglePane)
    runCurrent()
    viewModel.onAction(TerminalAction.PaneOpened(10, "/repo/a", isHome = false))
    runCurrent()
    assertEquals(3L, viewModel.state.value.activeTabId, "The newer /repo/a tab was used last, so it wins over the older one.")
    assertEquals(3, viewModel.state.value.tabs.size)

    viewModel.onAction(TerminalAction.PaneOpened(11, "/repo/b", isHome = false))
    runCurrent()
    assertEquals(3L, viewModel.state.value.activeTabId, "A second report in the same opening changes nothing.")

    viewModel.onAction(TerminalAction.TogglePane)
    runCurrent()
    viewModel.onAction(TerminalAction.TogglePane)
    runCurrent()
    viewModel.onAction(TerminalAction.PaneOpened(12, "/repo/c", isHome = false))
    runCurrent()
    assertEquals(12L, viewModel.state.value.activeTabId)
    assertEquals(4, viewModel.state.value.tabs.size)
  }

  @Test
  fun `closing the active tab activates the most recently used one and the last tab closes the pane`() = runTest(dispatcher) {
    val viewModel = TerminalViewModel(details, settings)
    runCurrent()
    viewModel.onAction(TerminalAction.TogglePane)
    viewModel.openTabs("/repo/a", "/repo/b", "/repo/c")
    viewModel.onAction(TerminalAction.SelectTab(1))
    viewModel.onAction(TerminalAction.SelectTab(2))
    runCurrent()

    viewModel.onAction(TerminalAction.CloseTab(2))
    runCurrent()
    assertEquals(1L, viewModel.state.value.activeTabId)
    assertEquals(listOf(1L, 3L), viewModel.state.value.tabs.map { it.id })

    viewModel.onAction(TerminalAction.TabsEnded(setOf(99)))
    runCurrent()
    assertTrue(viewModel.state.value.paneOpen, "An exit for a tab that is already gone must not close the pane.")

    viewModel.onAction(TerminalAction.TabsEnded(setOf(1, 3)))
    runCurrent()
    assertTrue(viewModel.state.value.tabs.isEmpty())
    assertNull(viewModel.state.value.activeTabId)
    assertFalse(settings.updates.last().terminalPaneOpen)
    assertFalse(viewModel.state.value.paneOpen)
  }

  @Test
  fun `tab labels name the directory, number duplicates in creation order and renumber after a removal`() {
    fun labels(vararg tabs: TerminalTab) = terminalTabLabels(tabs.toList()).map { it.label }
    val home = TerminalTab(1, "/Users/me", isHome = true, label = "")
    val trailingSlash = TerminalTab(2, "/repo/skill-bill/", isHome = false, label = "")
    val root = TerminalTab(3, "/", isHome = false, label = "")
    val windows = TerminalTab(4, "C:\\work\\docs\\", isHome = false, label = "")
    val first = TerminalTab(5, "/x/repo", isHome = false, label = "")
    val second = TerminalTab(6, "/y/repo", isHome = false, label = "")

    assertEquals(listOf("~", "skill-bill", "/", "docs"), labels(home, trailingSlash, root, windows))
    assertEquals(listOf("repo 1", "docs", "repo 2"), labels(first, windows, second))
    assertEquals(listOf("docs", "repo"), labels(windows, second))
  }
}
