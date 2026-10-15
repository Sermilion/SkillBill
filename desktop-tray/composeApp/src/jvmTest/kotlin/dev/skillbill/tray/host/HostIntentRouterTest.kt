package dev.skillbill.tray.host

import dev.skillbill.tray.WindowFakes
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.testing.FakeWorkStoreSession
import dev.skillbill.tray.feature.settings.SettingsRoute
import dev.skillbill.tray.lifetime.SessionTransitionCoordinator
import dev.skillbill.tray.lifetime.WindowRegistry
import dev.skillbill.tray.navigation.Section
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostIntentRouterTest {
  private val item = WorkIdentity("goal-1", "repo-1")
  private val other = WorkIdentity("goal-2", "repo-2")
  private var refreshes = 0
  private var quits = 0

  private class Host(val router: HostIntentRouter, val windows: WindowRegistry)

  private fun TestScope.host(): Host {
    val transitions = SessionTransitionCoordinator(backgroundScope, FakeWorkStoreSession(), StandardTestDispatcher(testScheduler))
    val windows = WindowRegistry(backgroundScope, WindowFakes()::windowComponent, transitions)
    val router = HostIntentRouter(backgroundScope, windows, { refreshes++ }, { quits++ })
    runCurrent()
    return Host(router, windows)
  }

  @Test
  fun `an intent is applied on the router's dispatcher, not on the thread that sent it`() = runTest {
    val host = host()
    val window = host.windows.openMain()

    // A joined thread, not a context switch: resuming after withContext would queue behind the router and let it run first.
    thread { host.router.dispatch(HostIntent.OpenWork(item)) }.join()
    assertNull(window.navigator.selected, "Nothing is applied until the UI dispatcher runs it.")

    runCurrent()
    assertEquals(item, window.navigator.selected)
  }

  @Test
  fun `with no main window an intent opens one`() = runTest {
    val host = host()

    host.router.dispatch(HostIntent.OpenWork(item))
    runCurrent()

    val window = host.windows.windows.single()
    assertEquals(item, window.navigator.selected)
    assertTrue(window.visible)
    assertSame(window, host.windows.active)
  }

  @Test
  fun `a hidden main window is shown and used`() = runTest {
    val host = host()
    val window = host.windows.openMain()
    window.hide()

    host.router.dispatch(HostIntent.OpenWork(item))
    runCurrent()

    assertTrue(window.visible)
    assertEquals(item, window.navigator.selected)
    assertEquals(1, host.windows.windows.size)
  }

  @Test
  fun `with several windows the one used last gets the item and the others are untouched`() = runTest {
    val host = host()
    val one = host.windows.openMain()
    val two = host.windows.openMain()
    host.windows.activate(one)

    host.router.dispatch(HostIntent.OpenWork(item))
    runCurrent()

    assertEquals(item, one.navigator.selected)
    assertNull(two.navigator.selected)
    assertEquals(2, host.windows.windows.size)

    host.windows.activate(two)
    host.router.dispatch(HostIntent.OpenWork(other))
    runCurrent()
    assertEquals(other, two.navigator.selected)
    assertEquals(item, one.navigator.selected)
  }

  @Test
  fun `a work item opens in the work section even when the window shows history`() = runTest {
    val host = host()
    val window = host.windows.openMain()
    window.navigator.switchTo(Section.HISTORY)

    host.router.dispatch(HostIntent.OpenWork(item))
    runCurrent()

    assertEquals(Section.WORK, window.navigator.section)
    assertEquals(item, window.navigator.selected)
  }

  @Test
  fun `repeated activation of the same item is one request`() = runTest {
    val host = host()
    val window = host.windows.openMain()

    repeat(3) { host.router.dispatch(HostIntent.OpenWork(item)) }
    host.router.dispatch(HostIntent.Refresh)
    host.router.dispatch(HostIntent.Refresh)
    host.router.dispatch(HostIntent.OpenWork(other))
    runCurrent()

    assertEquals(1, refreshes)
    assertEquals(other, window.navigator.selected, "Distinct intents still apply, in the order they arrived.")
  }

  @Test
  fun `show main brings a hidden window forward without changing what it shows`() = runTest {
    val host = host()
    val window = host.windows.openMain()
    window.navigator.openDetail(item)
    window.hide()

    host.router.dispatch(HostIntent.ShowMain)
    runCurrent()

    assertTrue(window.visible)
    assertEquals(item, window.navigator.selected)
  }

  @Test
  fun `settings opens its own window and quit asks the application to exit`() = runTest {
    val host = host()
    host.windows.openMain()

    host.router.dispatch(HostIntent.OpenSettings)
    host.router.dispatch(HostIntent.Quit)
    runCurrent()

    assertTrue(host.windows.windows.any { it.navigator.stack.root is SettingsRoute })
    assertEquals(1, quits)
  }
}
