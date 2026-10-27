package dev.skillbill.tray.terminal

import com.jediterm.terminal.ui.settings.SettingsProvider
import dev.skillbill.tray.feature.terminal.TerminalTabs
import dev.skillbill.tray.lifetime.WindowOwner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job

/**
 * One [TerminalSession] per window id and tab id, so switching tabs, closing the pane or reopening it on another project never
 * ends a shell that is still running. A session ends when its tab is dropped from [retainOnly], when its shell exits on its
 * own (the tab id is then published through [endedTabs]), when its window's scope completes (which [WindowOwner.dispose]
 * triggers), and, for every session still open, when the JVM shuts down. A window whose scope has already completed gets a
 * session that is closed and removed at once.
 *
 * Tab ids come from [newTabId], a process-wide counter, so no tab ever claims the id of an earlier shell. Each window's tab list
 * ([tabsFor]) is kept here with its shells and for as long, so the view model a database switch recreates finds the same tabs and
 * a switch ends no shell.
 */
class TerminalSessions(
  private val processFactory: TerminalProcessFactory,
  private val attach: (TerminalProcess, SettingsProvider) -> TerminalView,
  private val addShutdownHook: (Thread) -> Unit,
) {
  private val sessions = ConcurrentHashMap<Key, TerminalSession>()
  private val ended = ConcurrentHashMap<Int, MutableStateFlow<Set<Long>>>()
  private val tabLists = ConcurrentHashMap<Int, TerminalTabs>()
  private val hookInstalled = AtomicBoolean(false)
  private val nextTabId = AtomicLong(1)

  fun newTabId(): Long = nextTabId.getAndIncrement()

  /**
   * The tabs of [window] whose shell exited on its own and whose tab has not been dropped yet. A state flow, so an exit that
   * happens while nothing collects (pane closed, window hidden) is still delivered to the next collector.
   */
  fun endedTabs(window: WindowOwner): StateFlow<Set<Long>> = endedFor(window).asStateFlow()

  /** The tab list of [window]; the same instance until the window's scope completes. */
  fun tabsFor(window: WindowOwner): TerminalTabs {
    tabLists[window.id]?.let { return it }
    val created = TerminalTabs()
    tabLists.putIfAbsent(window.id, created)?.let { return it }
    window.scope.coroutineContext.job.invokeOnCompletion { tabLists.remove(window.id, created) }
    return created
  }

  fun forTab(window: WindowOwner, tabId: Long): TerminalSession {
    if (hookInstalled.compareAndSet(false, true)) {
      addShutdownHook(Thread { sessions.values.forEach { closeQuietly(it) } })
    }
    val key = Key(window.id, tabId)
    sessions[key]?.let { return it }
    // A surface recomposed before its tab's exit lands must not start a new shell for a dead tab.
    if (tabId in endedFor(window).value) return TerminalSession(processFactory, attach) {}.also { closeQuietly(it) }
    lateinit var created: TerminalSession
    created = TerminalSession(processFactory, attach) {
      if (sessions.remove(key, created)) endedFor(window).update { it + tabId }
    }
    sessions.putIfAbsent(key, created)?.let { return it }
    // Registered after the map call has returned: on an already-completed scope the handler runs right here, and the map must
    // not be changed from inside one of its own compute functions.
    window.scope.coroutineContext.job.invokeOnCompletion {
      sessions.remove(key, created)
      closeQuietly(created)
    }
    return created
  }

  /** Ends every session of [window] whose tab is not in [tabIds]. Other windows and ids without a session are untouched. */
  fun retainOnly(window: WindowOwner, tabIds: Set<Long>) {
    sessions.entries.filter { it.key.windowId == window.id && it.key.tabId !in tabIds }.forEach { (key, session) ->
      if (sessions.remove(key, session)) closeQuietly(session)
    }
    // Ended ids the view model has dropped are delivered, so they stop piling up; undelivered ones are still in tabIds.
    ended[window.id]?.update { it intersect tabIds }
  }

  private fun endedFor(window: WindowOwner): MutableStateFlow<Set<Long>> {
    ended[window.id]?.let { return it }
    val created = MutableStateFlow<Set<Long>>(emptySet())
    ended.putIfAbsent(window.id, created)?.let { return it }
    window.scope.coroutineContext.job.invokeOnCompletion { ended.remove(window.id, created) }
    return created
  }

  private data class Key(val windowId: Int, val tabId: Long)

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
