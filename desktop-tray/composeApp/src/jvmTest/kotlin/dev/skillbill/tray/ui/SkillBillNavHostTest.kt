package dev.skillbill.tray.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.skillbill.tray.WindowFakes
import dev.skillbill.tray.composition.EntryComponent
import dev.skillbill.tray.core.testing.FakeWorkStoreSession
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.core.ui.navigation.rememberEntryComponent
import dev.skillbill.tray.lifetime.SessionState
import dev.skillbill.tray.lifetime.SessionTransitionCoordinator
import dev.skillbill.tray.lifetime.WindowRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
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
