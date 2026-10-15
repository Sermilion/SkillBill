package dev.skillbill.tray.host

import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.lifetime.WindowOwner
import dev.skillbill.tray.lifetime.WindowRegistry
import dev.skillbill.tray.navigation.Section
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What the tray, the tray menu and a notification ask the application to do; none of them reaches into a window directly. */
sealed interface HostIntent {
  /** Bring a main window forward. */
  data object ShowMain : HostIntent

  /** Open [identity]'s detail in a main window and bring it forward. */
  data class OpenWork(val identity: WorkIdentity) : HostIntent

  data object OpenSettings : HostIntent

  data object Refresh : HostIntent

  data object Quit : HostIntent
}

/**
 * Applies [HostIntent]s on the UI dispatcher, whichever thread they come from: a tray callback or a notification action may run on
 * a native or AWT thread, and window state is only touched from [scope], which must run on the UI dispatcher.
 *
 * Where an intent lands: the main window used last if it is still open, else a visible main window, else a hidden one, which it
 * shows; with no main window at all it opens one. Equal intents that arrive back to back are one request, so repeated
 * activation of the same item opens it once.
 */
class HostIntentRouter(
  scope: CoroutineScope,
  private val windows: WindowRegistry,
  private val refresh: suspend () -> Unit,
  private val quit: () -> Unit,
) {
  private val queue = Channel<HostIntent>(Channel.UNLIMITED)

  init {
    scope.launch {
      for (first in queue) {
        val burst = mutableListOf(first)
        while (true) burst += queue.tryReceive().getOrNull() ?: break
        // Only back-to-back repeats collapse, so the last of interleaved requests (A, B, A) is still the one that shows.
        burst.filterIndexed { index, intent -> index == 0 || burst[index - 1] != intent }.forEach { handle(it) }
      }
    }
  }

  /** Queues [intent]; safe from any thread, and never blocks. */
  fun dispatch(intent: HostIntent) {
    queue.trySend(intent)
  }

  private suspend fun handle(intent: HostIntent) {
    when (intent) {
      HostIntent.ShowMain -> bringForward(mainWindow())
      is HostIntent.OpenWork -> {
        val window = mainWindow()
        window.navigator.switchTo(Section.WORK)
        window.navigator.openDetail(intent.identity)
        bringForward(window)
      }
      HostIntent.OpenSettings -> bringForward(windows.openSettings())
      HostIntent.Refresh -> refresh()
      HostIntent.Quit -> quit()
    }
  }

  private fun mainWindow(): WindowOwner = windows.target() ?: windows.openMain()

  private fun bringForward(window: WindowOwner) {
    window.bringToFront()
    windows.activate(window)
  }
}
