package dev.skillbill.tray.lifetime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.skillbill.tray.composition.EntryComponents
import dev.skillbill.tray.composition.WindowComponent
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.navigation.WindowNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns everything one window holds: its [navigator], its coroutine [scope], the [entryStoreOwner] its entries' ViewModels live
 * in, and the [component] its entries are built from. A window is shown, hidden and disposed independently of every other
 * window:
 *
 *  - [hide] only makes it invisible; the navigator, the entries' ViewModels and the scope stay, so showing it again is exactly
 *    what was hidden.
 *  - [show] makes it visible; [bringToFront] also asks the platform window to come to the front and take focus.
 *  - [resetSession] ends the entries of the database generation that is ending: their store is cleared, which cancels every
 *    entry ViewModel's collectors, and a fresh store is installed for the next generation.
 *  - [dispose] ends it: the scope is cancelled and every ViewModel in the current store is cleared. It is the window's own end
 *    and touches no other window.
 *
 * The application ends all of them in order when it exits; see the application owner.
 */
class WindowOwner(
  val id: Int,
  appScope: CoroutineScope,
  val navigator: WindowNavigator,
  private val component: WindowComponent,
) {
  /** The store entries live in, one per database generation; a new one replaces it when the generation ends. */
  var entryStoreOwner: ViewModelStoreOwner by mutableStateOf(SessionEntryStore())
    private set

  val scope: CoroutineScope = CoroutineScope(SupervisorJob(appScope.coroutineContext[Job]) + appScope.coroutineContext.minusKey(Job))

  val entries: EntryComponents get() = component.entries

  var visible: Boolean by mutableStateOf(true)
    private set

  var disposed: Boolean = false
    private set

  /** Counts focus requests; a counter, not a flag, so a repeat request for an already visible window still triggers. */
  var focusRequests: Int by mutableStateOf(0)
    private set

  fun show() {
    if (!disposed) visible = true
  }

  /** Shows the window and asks the platform window to be raised and focused; the host window observes [focusRequests]. */
  fun bringToFront() {
    if (disposed) return
    visible = true
    focusRequests++
  }

  fun hide() {
    visible = false
  }

  /**
   * The database generation ended ([ref] is null) or a new one began. A main window drops the entries of the old generation
   * before its routes are reset, so no old ViewModel keeps collecting against the shared repositories; a settings window has no
   * generation-bound entries and keeps its store.
   */
  fun resetSession(ref: DatabaseGenerationRef?) {
    if (ref == null && navigator.section != null) {
      val old = entryStoreOwner
      entryStoreOwner = SessionEntryStore()
      old.viewModelStore.clear()
    }
    navigator.reset(ref)
  }

  fun dispose() {
    if (disposed) return
    disposed = true
    visible = false
    scope.cancel()
    entryStoreOwner.viewModelStore.clear()
  }
}

private class SessionEntryStore : ViewModelStoreOwner {
  override val viewModelStore: ViewModelStore = ViewModelStore()
}
