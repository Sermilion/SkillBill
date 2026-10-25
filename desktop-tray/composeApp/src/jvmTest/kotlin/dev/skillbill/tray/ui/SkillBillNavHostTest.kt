package dev.skillbill.tray.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
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
import dev.skillbill.tray.feature.terminal.TerminalViewModel
import dev.skillbill.tray.lifetime.SessionState
import dev.skillbill.tray.lifetime.SessionTransitionCoordinator
import dev.skillbill.tray.lifetime.WindowRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
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
        terminal = rememberTerminalViewModel(window)
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          terminal = terminal,
          terminalSurface = { Box(Modifier.testTag("terminal-surface")) },
        )
      }
      waitForIdle()
      onNodeWithTag("terminal-surface").assertDoesNotExist()

      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      onNodeWithText("Terminal").assertExists()
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
  fun `the terminal keeps its directory while open and the next opening takes the latest selection's project`() = runComposeUiTest {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
      val window = windows.openMain()
      val item = workItem("a")
      fakes.details.onDetail = { Outcome.Value(detail(item)) }
      val opened = mutableListOf<String?>()
      lateinit var terminal: TerminalViewModel

      setContent {
        terminal = rememberTerminalViewModel(window)
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          terminal = terminal,
          terminalSurface = { directory -> opened += directory },
        )
      }
      WorkSection.entries.forEach { fakes.boards.emit(it, Outcome.Value(board(emptyList()))) }
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      assertNull(opened.last())

      runOnIdle { window.navigator.openDetail(item.identity) }
      waitForIdle()
      assertNull(opened.last(), "Selecting work while the pane is open never swaps its shell.")

      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      terminal.onAction(TerminalAction.TogglePane)
      waitForIdle()
      assertEquals("/repo/a", opened.last())
      window.dispose()
    } finally {
      Dispatchers.resetMain()
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
      val opened = mutableListOf<String?>()
      lateinit var terminal: TerminalViewModel

      setContent {
        terminal = rememberTerminalViewModel(window)
        SkillBillNavHost(
          window,
          SessionState.Active(DatabaseGenerationRef(0)),
          null,
          {},
          {},
          specs = specs,
          terminal = terminal,
          terminalSurface = { directory -> opened += directory },
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
      assertNull(opened.last())

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
