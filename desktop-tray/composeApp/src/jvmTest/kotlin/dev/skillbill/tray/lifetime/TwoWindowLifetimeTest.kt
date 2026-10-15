package dev.skillbill.tray.lifetime

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.skillbill.tray.ClearableViewModel
import dev.skillbill.tray.WindowFakes
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.testing.FakeWorkStoreSession
import dev.skillbill.tray.feature.settings.SettingsRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TwoWindowLifetimeTest {
  private val fakes = WindowFakes()
  private val session = FakeWorkStoreSession()

  private fun TestScope.registry(): WindowRegistry {
    val transitions = SessionTransitionCoordinator(backgroundScope, session, StandardTestDispatcher(testScheduler))
    return WindowRegistry(backgroundScope, fakes::windowComponent, transitions)
  }

  private fun WindowOwner.holdViewModel(): ClearableViewModel =
    ViewModelProvider.create(entryStoreOwner, viewModelFactory { initializer { ClearableViewModel() } })[ClearableViewModel::class]

  private val WindowOwner.scopeActive: Boolean get() = scope.coroutineContext[Job]!!.isActive

  @Test
  fun `two windows own separate navigators, scopes, stores and graphs`() = runTest {
    val registry = registry()
    val one = registry.openMain()
    val two = registry.openMain()

    assertNotSame(one.navigator, two.navigator)
    assertNotSame(one.entryStoreOwner.viewModelStore, two.entryStoreOwner.viewModelStore)
    assertNotSame(one.scope, two.scope)
    assertNotSame(one.entries, two.entries)
    assertNotSame(one.holdViewModel(), two.holdViewModel())

    one.navigator.openDetail(WorkIdentity("goal-1", "repo-1"))
    assertEquals(WorkIdentity("goal-1", "repo-1"), one.navigator.selected)
    assertNull(two.navigator.selected)
  }

  @Test
  fun `each window builds its entry graphs on its own`() = runTest {
    val registry = registry()
    val one = registry.openMain()
    val two = registry.openMain()

    one.entries.create()
    one.entries.create()
    two.entries.create()

    assertEquals(3, fakes.entryComponentsCreated)
    assertNotSame(one.entries.create().viewModels, one.entries.create().viewModels)
  }

  @Test
  fun `hiding a window keeps its navigator, its ViewModels and its scope`() = runTest {
    val registry = registry()
    val window = registry.openMain()
    window.navigator.openDetail(WorkIdentity("goal-1", "repo-1"))
    val held = window.holdViewModel()

    window.hide()

    assertFalse(window.visible)
    assertFalse(window.disposed)
    assertTrue(window.scopeActive)
    assertFalse(held.cleared)
    assertEquals(WorkIdentity("goal-1", "repo-1"), window.navigator.selected)
    assertTrue(registry.windows.contains(window))

    window.show()
    assertTrue(window.visible)
    assertSame(held, window.holdViewModel())
  }

  @Test
  fun `disposing a window ends only that window`() = runTest {
    val registry = registry()
    val one = registry.openMain()
    val two = registry.openMain()
    val heldOne = one.holdViewModel()
    val heldTwo = two.holdViewModel()

    registry.dispose(one)

    assertTrue(one.disposed)
    assertFalse(one.scopeActive)
    assertTrue(heldOne.cleared)
    assertEquals(listOf(two), registry.windows)
    assertFalse(two.disposed)
    assertTrue(two.scopeActive)
    assertFalse(heldTwo.cleared)
    assertSame(two, registry.active)
  }

  @Test
  fun `a disposed window cannot be shown again`() = runTest {
    val registry = registry()
    val window = registry.openMain()

    registry.dispose(window)
    window.show()

    assertFalse(window.visible)
    assertTrue(registry.windows.isEmpty())
  }

  @Test
  fun `exit disposes every window and the main stack is not kept for a later start`() = runTest {
    val registry = registry()
    val one = registry.openMain()
    val two = registry.openMain()
    val heldOne = one.holdViewModel()
    val heldTwo = two.holdViewModel()
    one.navigator.openDetail(WorkIdentity("goal-1", "repo-1"))

    registry.disposeAll()

    assertTrue(registry.windows.isEmpty())
    assertTrue(one.disposed && two.disposed)
    assertTrue(heldOne.cleared && heldTwo.cleared)
    assertFalse(one.scopeActive || two.scopeActive)
    assertNull(registry.openMain().navigator.selected)
  }

  @Test
  fun `a closed main window leaves its stack for the next one`() = runTest {
    val registry = registry()
    val window = registry.openMain()
    window.navigator.openDetail(WorkIdentity("goal-1", "repo-1"))

    registry.dispose(window)

    assertEquals(WorkIdentity("goal-1", "repo-1"), registry.openMain().navigator.selected)
  }

  @Test
  fun `the settings window is one instance and is not a target for work`() = runTest {
    val registry = registry()
    val main = registry.openMain()

    val settings = registry.openSettings()
    settings.hide()
    val again = registry.openSettings()

    assertSame(settings, again)
    assertTrue(again.visible)
    assertTrue(settings.navigator.stack.root is SettingsRoute)
    assertSame(main, registry.target())
  }

  @Test
  fun `a host target is the active main window, else a visible one, else a hidden one`() = runTest {
    val registry = registry()
    assertNull(registry.target())
    val one = registry.openMain()
    val two = registry.openMain()

    registry.activate(one)
    assertSame(one, registry.target())

    registry.dispose(one)
    assertSame(two, registry.target())

    two.hide()
    assertSame(two, registry.target())
  }
}
