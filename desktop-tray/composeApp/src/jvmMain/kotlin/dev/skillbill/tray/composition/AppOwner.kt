package dev.skillbill.tray.composition

import dev.skillbill.tray.application.NativeServices
import dev.skillbill.tray.application.StoreSessionCoordinator
import dev.skillbill.tray.application.TransitionNotificationCoordinator
import dev.skillbill.tray.application.TrayAvailability
import dev.skillbill.tray.host.HostIntent
import dev.skillbill.tray.host.HostIntentRouter
import dev.skillbill.tray.lifetime.SessionTransitionCoordinator
import dev.skillbill.tray.lifetime.WindowRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns what lives as long as the application: the scopes, the database session transitions, the window registry, the host
 * intent router and the coordinators that keep the store, the tray and the notifications working. Windows are owned by the
 * [windows] registry and the selected database by the application component's store session; this class only decides the order
 * in which all of that ends.
 *
 * Hiding a window and disposing one touch only that window. Exiting is [quit]: every window is disposed, then the coordinators
 * stop, and only then the database session and the settings store close, so nothing reads a store that is already closed.
 */
class AppOwner(
  private val application: ApplicationComponent,
  native: NativeServices,
  private val outliving: CoroutineScope,
  private val releaseViewModels: () -> Unit,
  private val exit: () -> Unit,
  refresh: suspend () -> Unit,
) {
  private val ui = application.dispatchers.ui
  private val quitting = AtomicBoolean(false)
  private val appScope = CoroutineScope(SupervisorJob() + ui)
  private val shellScope = CoroutineScope(SupervisorJob() + application.dispatchers.io)

  // Single-threaded, because the board sections the notifications read are not synchronised.
  private val notificationScope = CoroutineScope(SupervisorJob() + ui)

  val transitions = SessionTransitionCoordinator(appScope, application.storeSession, ui)
  val windows = WindowRegistry(appScope, { application.windows.create() }, transitions)
  val storeSession = StoreSessionCoordinator(
    shellScope,
    application.settings,
    transitions::switchTo,
    application.storeSession::checkRuntimeBuild,
    native,
  )

  // Every refresh re-reads the installed runtime's build record first, so a reinstalled runtime of another build closes the
  // open database and a reinstalled matching one is picked up, both without a restart.
  val router = HostIntentRouter(appScope, windows, { withContext(application.dispatchers.io) { storeSession.retry() }; refresh() }, ::quit)
  val tray = TrayAvailability(shellScope, native)

  private val notifications = TransitionNotificationCoordinator(notificationScope, application.boards, application.settings, native) { identity ->
    router.dispatch(HostIntent.OpenWork(identity))
  }

  init {
    transitions.onReset { ref -> if (ref == null) notifications.resetBaseline() }
    windows.openMain()
  }

  /** Ends the application: windows, then coordinators, then the stores. [exit] runs once everything is closed. */
  fun quit() {
    if (!quitting.compareAndSet(false, true)) return
    outliving.launch {
      windows.disposeAll()
      releaseViewModels()
      shellScope.coroutineContext[Job]?.cancelAndJoin()
      notificationScope.coroutineContext[Job]?.cancelAndJoin()
      appScope.coroutineContext[Job]?.cancelAndJoin()
      application.shutdown.closeAndJoin()
      exit()
    }
  }

  /** Stops the coordinators when the application composition itself is torn down; the stores are closed by [quit]. */
  fun cancelScopes() {
    application.launches.cancel()
    shellScope.coroutineContext[Job]?.cancel()
    notificationScope.coroutineContext[Job]?.cancel()
    appScope.coroutineContext[Job]?.cancel()
  }
}
