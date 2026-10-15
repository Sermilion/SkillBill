package dev.skillbill.tray.lifetime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.savedstate.SavedState
import dev.skillbill.tray.composition.WindowComponent
import dev.skillbill.tray.core.ui.navigation.AppRoute
import dev.skillbill.tray.feature.settings.SettingsRoute
import dev.skillbill.tray.feature.work.WorkRoute
import dev.skillbill.tray.navigation.RouteSnapshots
import dev.skillbill.tray.navigation.WindowNavigator
import kotlinx.coroutines.CoroutineScope

/**
 * The windows the application has open. Each is a [WindowOwner] with a graph and navigator of its own, so nothing is shared
 * between two windows but the application's own services. The registry is also where a database switch reaches every window, and
 * where a host intent finds the window it should land in.
 */
class WindowRegistry(
  private val appScope: CoroutineScope,
  private val components: () -> WindowComponent,
  private val transitions: SessionTransitionCoordinator,
) {
  private val owners = mutableStateListOf<WindowOwner>()
  private var nextId = 1
  private var lastClosed: SavedState? = null

  /** The window the user used last, or null before any was used. Snapshot state, so a composable reading [target] sees it change. */
  var active: WindowOwner? by mutableStateOf(null)
    private set

  val windows: List<WindowOwner> get() = owners

  init {
    transitions.onReset { ref -> owners.forEach { it.resetSession(ref) } }
  }

  /**
   * Opens a main window. It restores the stack the last closed main window had when that is still valid for the open session;
   * otherwise it starts at the work list.
   */
  fun openMain(): WindowOwner {
    val ref = transitions.current
    val fallback = WorkRoute(ref)
    val routes = lastClosed?.let { RouteSnapshots.restore(it, ref, fallback).routes } ?: listOf(fallback)
    return open(routes)
  }

  /** The settings window: the one that exists, shown again, or a new one rooted at settings. */
  fun openSettings(): WindowOwner {
    val existing = owners.firstOrNull { it.navigator.stack.root is SettingsRoute }
    if (existing != null) {
      existing.show()
      activate(existing)
      return existing
    }
    return open(listOf(SettingsRoute))
  }

  fun activate(window: WindowOwner) {
    if (!window.disposed) active = window
  }

  /** Ends one window: it leaves the registry, its main stack is kept for the next main window, and its owner is disposed. */
  fun dispose(window: WindowOwner) {
    if (!owners.remove(window)) return
    if (window.navigator.section != null) lastClosed = window.navigator.snapshot()
    if (active === window) active = owners.lastOrNull { it.visible } ?: owners.lastOrNull()
    window.dispose()
  }

  /** Ends every window; the application is exiting. */
  fun disposeAll() {
    owners.toList().forEach(::dispose)
    lastClosed = null
  }

  /** The window a host intent lands in: the one used last when it is still open, else a visible one, else a hidden one. */
  fun target(): WindowOwner? {
    val main = owners.filter { it.navigator.section != null }
    return main.firstOrNull { it === active } ?: main.lastOrNull { it.visible } ?: main.lastOrNull()
  }

  private fun open(routes: List<AppRoute>): WindowOwner {
    val owner = WindowOwner(
      id = nextId++,
      appScope = appScope,
      navigator = WindowNavigator({ transitions.current }, routes),
      component = components(),
    )
    owners.add(owner)
    active = owner
    return owner
  }
}
