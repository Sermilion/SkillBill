package dev.skillbill.tray.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.skillbill.tray.WindowFakes
import dev.skillbill.tray.composition.EntryComponent
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeWorkStoreSession
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.testing.FakeSpecBrowserRepository
import dev.skillbill.tray.core.testing.FakeWorkControlRepository
import dev.skillbill.tray.core.testing.TestClock
import dev.skillbill.tray.core.testing.board
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.core.ui.navigation.rememberEntryComponent
import dev.skillbill.tray.core.ui.LaunchRequests
import dev.skillbill.tray.feature.specs.SpecsAction
import dev.skillbill.tray.feature.specs.SpecsViewModel
import dev.skillbill.tray.feature.terminal.TerminalAction
import dev.skillbill.tray.feature.terminal.TerminalTabs
import dev.skillbill.tray.feature.terminal.TerminalViewModel
import dev.skillbill.tray.lifetime.SessionState
import dev.skillbill.tray.lifetime.SessionTransitionCoordinator
import dev.skillbill.tray.lifetime.WindowOwner
import dev.skillbill.tray.lifetime.WindowRegistry
import dev.skillbill.tray.terminal.ResolvedTerminalDirectory
import dev.skillbill.tray.terminal.TerminalTabHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SkillBillNavHostTest {
  private val fakes = WindowFakes()
  private val testScope = TestScope()
  private val transitions = SessionTransitionCoordinator(
    testScope.backgroundScope,
    FakeWorkStoreSession(),
    StandardTestDispatcher(testScope.testScheduler),
  )
  private val windows = WindowRegistry(testScope.backgroundScope, fakes::windowComponent, transitions)

  @Test
  fun `a main window shows the sidebar with its section links`() = runComposeUiTest {
    val window = windows.openMain()

    setContent { SkillBillNavHost(window, SessionState.Active(DatabaseGenerationRef(0)), null, {}, {}) }
    waitForIdle()

    onNodeWithText("History").assertExists()
    onNodeWithText("Settings").assertExists()
    onNodeWithText("Opening your database").assertDoesNotExist()
    window.dispose()
  }

  @Test
  fun `the terminal pane follows its open state and a drag saves the height once on release`() = runComposeUiTest {
    // The ViewModel writes settings on Main; running Main unconfined lands each write before the next waitForIdle.
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
      val window = windows.openMain()
      lateinit var terminal: TerminalViewModel

      setContent {
        terminal = rememberTerminalViewModel(window, remember { TerminalTabs() })
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          terminal = terminal,
          terminalTabs = FakeTerminalTabHost(),
          terminalSurface = { Box(Modifier.testTag("terminal-surface")) },
        )
      }
      waitForIdle()
      onNodeWithTag("terminal-surface").assertDoesNotExist()

      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      onNodeWithContentDescription("Close terminal").assertExists()
      // A session takes its directory only at its first launch, so the surface waits for the list to settle the first selection.
      onNodeWithTag("terminal-surface").assertDoesNotExist()

      WorkSection.entries.forEach { fakes.boards.emit(it, Outcome.Value(board(emptyList()))) }
      waitForIdle()
      onNodeWithTag("terminal-surface").assertExists()

      val writes = fakes.settings.updates.size
      onNodeWithTag(TERMINAL_DIVIDER_TAG).performTouchInput {
        down(center)
        repeat(4) { moveBy(Offset(0f, -50f)) }
        up()
      }
      waitForIdle()

      assertEquals(writes + 1, fakes.settings.updates.size, "One drag saves one height, not one per pixel.")
      val saved = fakes.settings.updates.last().terminalPaneHeightDp
      assertTrue(saved > AppSettings().terminalPaneHeightDp && saved <= AppSettings.TERMINAL_PANE_MAX_HEIGHT_DP, "Dragging up grows the pane: $saved.")
      window.dispose()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `an opening creates one tab for the latest pick, a pick while open changes no tab and a reopening activates its directory's tab`() = runComposeUiTest {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
      val window = windows.openMain()
      val item = workItem("a")
      fakes.details.onDetail = { Outcome.Value(detail(item)) }
      lateinit var terminal: TerminalViewModel

      setContent {
        terminal = rememberTerminalViewModel(window, remember { TerminalTabs() })
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          terminal = terminal,
          terminalTabs = FakeTerminalTabHost(),
          terminalSurface = { Box(Modifier.testTag("terminal-surface")) },
        )
      }
      WorkSection.entries.forEach { fakes.boards.emit(it, Outcome.Value(board(emptyList()))) }
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      assertEquals(listOf("/home"), terminal.state.value.tabs.map { it.directory })
      val home = terminal.state.value.activeTabId

      runOnIdle { window.navigator.openDetail(item.identity) }
      waitForIdle()
      assertEquals(listOf("/home"), terminal.state.value.tabs.map { it.directory }, "A pick while the pane is open creates no tab.")
      assertEquals(home, terminal.state.value.activeTabId, "A pick while the pane is open switches no tab.")

      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      assertEquals(listOf("/home", "/repo/a"), terminal.state.value.tabs.map { it.directory })
      val repo = terminal.state.value.activeTabId

      terminal.onAction(TerminalAction.SelectTab(home!!))
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      assertEquals(2, terminal.state.value.tabs.size, "Reopening in a directory that has a tab creates none.")
      assertEquals(repo, terminal.state.value.activeTabId)
      window.dispose()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `the tab strip exposes tab semantics, close controls, arrow key selection and a new-tab button`() = runComposeUiTest {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
      val window = windows.openMain()
      lateinit var terminal: TerminalViewModel

      setContent {
        terminal = rememberTerminalViewModel(window, remember { TerminalTabs() })
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          terminal = terminal,
          terminalTabs = FakeTerminalTabHost(),
          terminalSurface = { Box(Modifier.testTag("terminal-surface")) },
        )
      }
      WorkSection.entries.forEach { fakes.boards.emit(it, Outcome.Value(board(emptyList()))) }
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      onNodeWithText("~").assertIsSelected()

      onNodeWithContentDescription("New terminal tab").performClick()
      waitForIdle()
      onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(2)
      onNodeWithText("~ 2").assertIsSelected()
      onNodeWithText("~ 1").assertIsNotSelected()
      onNodeWithContentDescription("Close ~ 1 tab").assertExists()
      onNodeWithContentDescription("Close ~ 2 tab").assertExists()

      onNodeWithText("~ 2").performSemanticsAction(SemanticsActions.RequestFocus)
      onNodeWithText("~ 2").performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()
      onNodeWithText("~ 1").assertIsSelected()
      onNodeWithText("~ 1").assertIsFocused()

      onNodeWithContentDescription("Close ~ 1 tab").performClick()
      waitForIdle()
      assertEquals(listOf("~"), terminal.state.value.tabs.map { it.label })
      window.dispose()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `a tab is renamed in place from its rename action, Escape keeps the old name and Enter saves the typed one`() = runComposeUiTest {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
      val window = windows.openMain()
      lateinit var terminal: TerminalViewModel

      setContent {
        terminal = rememberTerminalViewModel(window, remember { TerminalTabs() })
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          terminal = terminal,
          terminalTabs = FakeTerminalTabHost(),
          terminalSurface = { Box(Modifier.testTag("terminal-surface")) },
        )
      }
      WorkSection.entries.forEach { fakes.boards.emit(it, Outcome.Value(board(emptyList()))) }
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      val rename = { label: String ->
        val actions = onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        runOnIdle { actions.single { it.label == "Rename tab" }.action() }
        waitForIdle()
      }

      rename("~")
      onNodeWithContentDescription("Tab name").performTextReplacement("discarded")
      onNodeWithContentDescription("Tab name").performKeyInput { pressKey(Key.Escape) }
      waitForIdle()
      assertEquals(listOf("~"), terminal.state.value.tabs.map { it.label })

      rename("~")
      onNodeWithContentDescription("Tab name").performTextReplacement("server")
      onNodeWithContentDescription("Tab name").performKeyInput { pressKey(Key.Enter) }
      waitForIdle()
      onNodeWithText("server").assertIsSelected()
      onNodeWithContentDescription("Close server tab").assertExists()
      window.dispose()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `a view model recreated by a database switch keeps the window's tabs and ends no shell`() = runComposeUiTest {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
      val window = windows.openMain()
      val host = FakeTerminalTabHost()
      var terminal by mutableStateOf(TerminalViewModel(fakes.details, fakes.settings, host.tabs(window)))

      setContent {
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          terminal = terminal,
          terminalTabs = host,
          terminalSurface = { Box(Modifier.testTag("terminal-surface")) },
        )
      }
      WorkSection.entries.forEach { fakes.boards.emit(it, Outcome.Value(board(emptyList()))) }
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      val tab = terminal.state.value.activeTabId!!
      val before = host.retained.size

      terminal = TerminalViewModel(fakes.details, fakes.settings, host.tabs(window))
      waitForIdle()

      assertEquals(listOf(tab), terminal.state.value.tabs.map { it.id })
      val afterSwap = host.retained.drop(before)
      assertTrue(afterSwap.isNotEmpty() && afterSwap.all { tab in it }, "No reconciliation may drop the open tab's shell: $afterSwap.")
      window.dispose()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `a shell that ended on its own removes its tab and the last one closes the pane`() = runComposeUiTest {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
      val window = windows.openMain()
      val host = FakeTerminalTabHost()
      lateinit var terminal: TerminalViewModel

      setContent {
        terminal = rememberTerminalViewModel(window, remember { TerminalTabs() })
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          terminal = terminal,
          terminalTabs = host,
          terminalSurface = { Box(Modifier.testTag("terminal-surface")) },
        )
      }
      WorkSection.entries.forEach { fakes.boards.emit(it, Outcome.Value(board(emptyList()))) }
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      val tab = terminal.state.value.activeTabId!!

      host.ended.value = setOf(tab)
      waitForIdle()

      assertTrue(terminal.state.value.tabs.isEmpty())
      assertFalse(terminal.state.value.paneOpen)
      assertEquals(emptySet(), host.retained.last(), "The ended tab's shell is no longer retained.")
      window.dispose()
    } finally {
      Dispatchers.resetMain()
    }
  }

  private class FakeTerminalTabHost : TerminalTabHost {
    private var next = 1L
    private val tabList = TerminalTabs()
    val ended = MutableStateFlow<Set<Long>>(emptySet())
    val retained = mutableListOf<Set<Long>>()

    override fun tabs(window: WindowOwner): TerminalTabs = tabList

    override fun newTabId(): Long = next++

    override fun resolveDirectory(requested: String?) =
      ResolvedTerminalDirectory(requested ?: "/home", requested?.substringAfterLast('/') ?: "~")

    override fun endedTabs(window: WindowOwner): StateFlow<Set<Long>> = ended

    override fun retainOnly(window: WindowOwner, tabIds: Set<Long>) {
      retained += tabIds
    }
  }

  @Test
  fun `the terminal opens in the project of the latest pick, a spec row or a re-picked sidebar item`() = runComposeUiTest {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
      val window = windows.openMain()
      val item = workItem("a")
      fakes.details.onDetail = { Outcome.Value(detail(item)) }
      val specRepository = FakeSpecBrowserRepository().apply {
        onProjects = { Outcome.Value(listOf(SpecProject("p", "Specs", "/repo/specs/.feature-specs", "/repo/specs"))) }
      }
      val specs = SpecsViewModel(
        specRepository,
        fakes.details,
        FakeWorkControlRepository(),
        fakes.settings,
        LaunchRequests(testScope.backgroundScope, FakeWorkControlRepository(), fakes.boards, TestClock(fixtureInstant)),
      )
      // Picked before the window composes, so it must never outrank what is picked later.
      specs.onAction(SpecsAction.Select("earlier"))
      val opened = mutableListOf<String>()
      lateinit var terminal: TerminalViewModel

      setContent {
        terminal = rememberTerminalViewModel(window, remember { TerminalTabs() })
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          specs = specs,
          terminal = terminal,
          terminalTabs = FakeTerminalTabHost(),
          terminalSurface = { tab -> opened += tab.directory },
        )
      }
      WorkSection.entries.forEach { fakes.boards.emit(it, Outcome.Value(board(emptyList()))) }
      // A pick and the toggle that follows it are separate input events, so the pick settles first.
      val openAndClose = {
        waitForIdle()
        terminal.onAction(TerminalAction.TogglePane)
        waitForIdle()
        terminal.onAction(TerminalAction.TogglePane)
        waitForIdle()
      }
      openAndClose()
      assertEquals("/home", opened.last(), "Nothing picked yet, so the shell starts in the home directory.")

      runOnIdle { window.navigator.openDetail(item.identity) }
      openAndClose()
      assertEquals("/repo/a", opened.last())

      runOnIdle { specs.onAction(SpecsAction.Select("bundle")) }
      openAndClose()
      assertEquals("/repo/specs", opened.last())

      runOnIdle { window.navigator.openDetail(item.identity) }
      openAndClose()
      assertEquals("/repo/a", opened.last(), "Re-picking the item already shown is still the latest pick.")
      window.dispose()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `while a database switch is under way nothing that names work renders`() = runComposeUiTest {
    val window = windows.openMain()

    setContent { SkillBillNavHost(window, SessionState.Transitioning, null, {}, {}) }
    waitForIdle()

    onNodeWithText("Opening your database").assertExists()
    onNodeWithText("History").assertDoesNotExist()
    assertEquals(0, fakes.entryComponentsCreated, "No entry is built for a session that is being replaced.")
    window.dispose()
  }

  @Test
  fun `routes made for an earlier database never render under the current one`() = runComposeUiTest {
    val window = windows.openMain()
    var sessionState by mutableStateOf<SessionState>(SessionState.Active(DatabaseGenerationRef(0)))

    setContent { SkillBillNavHost(window, sessionState, null, {}, {}) }
    waitForIdle()
    onNodeWithText("History").assertExists()

    sessionState = SessionState.Active(DatabaseGenerationRef(1))
    waitForIdle()

    onNodeWithText("Opening your database").assertExists()
    onNodeWithText("History").assertDoesNotExist()
    window.dispose()
  }

  @Test
  fun `an entry's component is built once per live entry and goes with its store`() = runComposeUiTest {
    var store = ViewModelStore()
    val owner = object : ViewModelStoreOwner {
      override val viewModelStore: ViewModelStore get() = store
    }
    var recompositions by mutableIntStateOf(0)
    val seen = mutableListOf<EntryComponent>()

    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val pass = recompositions
        val component = rememberEntryComponent { fakes.windowComponent().entries.create() }
        if (pass >= 0) seen += component
      }
    }
    waitForIdle()
    recompositions++
    waitForIdle()

    assertEquals(1, fakes.entryComponentsCreated, "Recomposing the entry builds nothing new.")
    assertSame(seen.first(), seen.last())

    store.clear()
    store = ViewModelStore()
    recompositions++
    waitForIdle()

    assertEquals(2, fakes.entryComponentsCreated, "A new entry store gets a new component.")
    assertNotSame(seen.first(), seen.last())
  }
}
