package dev.skillbill.tray.terminal

import com.jediterm.terminal.ui.settings.SettingsProvider
import dev.skillbill.tray.lifetime.WindowOwner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.job

/**
 * One [TerminalSession] per window id. A session is closed when its window's scope completes (which [WindowOwner.dispose]
 * triggers) and, for every session still open, when the JVM shuts down. A window whose scope has already completed gets a
 * session that is closed and removed at once.
 */
class TerminalSessions(
  private val processFactory: TerminalProcessFactory,
  private val attach: (TerminalProcess, SettingsProvider) -> TerminalView,
  private val addShutdownHook: (Thread) -> Unit,
) {
  private val sessions = ConcurrentHashMap<Int, TerminalSession>()
  private val hookInstalled = AtomicBoolean(false)

  fun forWindow(window: WindowOwner): TerminalSession {
    if (hookInstalled.compareAndSet(false, true)) {
      addShutdownHook(Thread { sessions.values.forEach { closeQuietly(it) } })
    }
    sessions[window.id]?.let { return it }
    val created = TerminalSession(processFactory, attach)
    sessions.putIfAbsent(window.id, created)?.let { return it }
    // Registered after the map call has returned: on an already-completed scope the handler runs right here, and the map must
    // not be changed from inside one of its own compute functions.
    window.scope.coroutineContext.job.invokeOnCompletion {
      sessions.remove(window.id, created)
      closeQuietly(created)
    }
    return created
  }

  // One failing close must not stop the remaining sessions from closing when the app quits, and must not surface as a
  // CompletionHandlerException when a window is disposed.
  private fun closeQuietly(session: TerminalSession) {
    try {
      session.close()
    } catch (_: Exception) {
      // The session is already marked closed and its view closed; there is nowhere left to report this.
    }
  }
}

// The app has one window registry, so window ids are unique for the process.
val appTerminalSessions = TerminalSessions(PtyTerminalProcessFactory, ::jediTermView, Runtime.getRuntime()::addShutdownHook)
