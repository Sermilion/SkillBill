package dev.skillbill.tray.lifetime

import dev.skillbill.tray.WindowFakes
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.testing.FakeWorkStoreSession
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.feature.work.WorkRoute
import dev.skillbill.tray.navigation.Section
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The barrier tests: a selection is held open on a barrier inside the data layer, and everything a window or the notification
 * baseline holds from the old session must already be gone while it is held, before the new session can render.
 */
class DatabaseGenerationTransitionTest {
  private val session = FakeWorkStoreSession()
  private val item = WorkIdentity("goal-1", "repo-1")
  private val events = mutableListOf<DatabaseGenerationRef?>()

  private class Setup(val transitions: SessionTransitionCoordinator, val registry: WindowRegistry)

  private fun TestScope.setup(): Setup {
    val transitions = SessionTransitionCoordinator(backgroundScope, session, StandardTestDispatcher(testScheduler))
    transitions.onReset { events += it }
    val registry = WindowRegistry(backgroundScope, WindowFakes()::windowComponent, transitions)
    return Setup(transitions, registry)
  }

  @Test
  fun `a database switch clears routes, selection, results and the baseline before the new session opens`() = runTest {
    val setup = setup()
    val transitions = setup.transitions
    val window = setup.registry.openMain()
    window.navigator.memory(Section.WORK).filter = "SKILL-4"
    window.navigator.memory(Section.WORK).scrollIndex = 9
    window.navigator.openDetail(item)
    assertEquals(DatabaseGenerationRef(0), (transitions.state.value as SessionState.Active).ref)

    val barrier = CompletableDeferred<Unit>()
    session.onSelect = { barrier.await() }
    val switching = launch { transitions.switchTo("/b.db") }
    runCurrent()

    // The old session is still open underneath, and nothing of it is left above.
    assertEquals(SessionState.Transitioning, transitions.state.value)
    assertNull(transitions.current)
    assertEquals(listOf<DatabaseGenerationRef?>(null), events)
    assertEquals(listOf(WorkRoute(null)), window.navigator.stack.entries)
    assertNull(window.navigator.selected)
    assertEquals("", window.navigator.memory(Section.WORK).filter)
    assertEquals(0, window.navigator.memory(Section.WORK).scrollIndex)
    assertFalse(window.navigator.openDetail(item))

    barrier.complete(Unit)
    runCurrent()
    switching.join()

    val ref = DatabaseGenerationRef(1)
    assertEquals(SessionState.Active(ref), transitions.state.value)
    assertEquals(listOf<DatabaseGenerationRef?>(null, ref), events)
    assertEquals(listOf(WorkRoute(ref)), window.navigator.stack.entries)
    assertEquals(listOf<String?>("/b.db"), session.selectedPaths)
  }

  @Test
  fun `every open window is reset, not only the active one`() = runTest {
    val setup = setup()
    val one = setup.registry.openMain()
    val two = setup.registry.openMain()
    one.navigator.openDetail(item)
    two.navigator.openDetail(item)
    session.onSelect = { check(one.navigator.selected == null && two.navigator.selected == null) }

    setup.transitions.switchTo(null)

    assertEquals(listOf(WorkRoute(DatabaseGenerationRef(1))), one.navigator.stack.entries)
    assertEquals(listOf(WorkRoute(DatabaseGenerationRef(1))), two.navigator.stack.entries)
  }

  @Test
  fun `a same-path replacement found by the data layer is a transition too`() = runTest {
    val setup = setup()
    val window = setup.registry.openMain()
    window.navigator.openDetail(item)

    session.replaceInPlace()
    runCurrent()

    val ref = DatabaseGenerationRef(1)
    assertEquals(SessionState.Active(ref), setup.transitions.state.value)
    assertEquals(listOf<DatabaseGenerationRef?>(null, ref), events)
    assertEquals(listOf(WorkRoute(ref)), window.navigator.stack.entries)
    assertNull(window.navigator.selected)
    assertEquals(emptyList(), session.selectedPaths)
  }

  @Test
  fun `selecting the same path again replaces the session and resets the windows like any other switch`() = runTest {
    val setup = setup()
    val window = setup.registry.openMain()
    window.navigator.openDetail(item)

    setup.transitions.switchTo("/a.db")
    setup.transitions.switchTo("/a.db")

    assertEquals(DatabaseGenerationRef(2), (setup.transitions.state.value as SessionState.Active).ref)
    assertEquals(listOf<DatabaseGenerationRef?>(null, DatabaseGenerationRef(1), null, DatabaseGenerationRef(2)), events)
    assertNull(window.navigator.selected)
  }

  @Test
  fun `a switch that is cancelled still settles, so no window is left waiting`() = runTest {
    val setup = setup()
    val window = setup.registry.openMain()
    window.navigator.openDetail(item)
    val barrier = CompletableDeferred<Unit>()
    session.onSelect = { barrier.await() }

    val switching = launch { setup.transitions.switchTo("/b.db") }
    runCurrent()
    assertEquals(SessionState.Transitioning, setup.transitions.state.value)
    switching.cancel()
    runCurrent()

    assertIs<SessionState.Active>(setup.transitions.state.value)
    assertEquals(listOf(WorkRoute((setup.transitions.state.value as SessionState.Active).ref)), window.navigator.stack.entries)
  }

  @Test
  fun `a second switch waits for the first to settle`() = runTest {
    val setup = setup()
    setup.registry.openMain()
    val barrier = CompletableDeferred<Unit>()
    session.onSelect = { path -> if (path == "/b.db") barrier.await() }

    val first = launch { setup.transitions.switchTo("/b.db") }
    runCurrent()
    val second = launch { setup.transitions.switchTo("/c.db") }
    runCurrent()
    assertEquals(listOf<String?>("/b.db"), session.selectedPaths)

    barrier.complete(Unit)
    runCurrent()
    first.join()
    second.join()

    assertEquals(listOf<String?>("/b.db", "/c.db"), session.selectedPaths)
    assertEquals(DatabaseGenerationRef(2), (setup.transitions.state.value as SessionState.Active).ref)
  }
}
