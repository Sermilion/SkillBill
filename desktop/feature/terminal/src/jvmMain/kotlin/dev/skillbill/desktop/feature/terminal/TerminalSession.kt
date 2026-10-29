package dev.skillbill.desktop.feature.terminal

import com.jediterm.terminal.ui.settings.SettingsProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

sealed interface TerminalStart {
  data class Started(val view: TerminalView) : TerminalStart

  data class Failed(val message: String) : TerminalStart

  data object Closed : TerminalStart
}

/**
 * One tab's terminal: at most one shell and one view, built on the first [open] and kept until [close] or until the shell exits on
 * its own, which calls [onExit] once. It lives outside composition, so hiding the pane never ends the shell. [onExit] runs on the
 * thread that saw the shell end, and never for a [close].
 */
class TerminalSession(
  private val launcher: ShellLauncher,
  private val attach: (ShellProcess, SettingsProvider) -> TerminalView,
  private val onExit: () -> Unit,
) {
  private val closing = AtomicBoolean(false)
  private var process: ShellProcess? = null
  private var view: TerminalView? = null

  /** Starts the shell on the first call; later calls return the same view whatever [launch] names. */
  @Synchronized
  fun open(launch: TerminalLaunch, settings: SettingsProvider): TerminalStart {
    if (closing.get()) return TerminalStart.Closed
    view?.let { return TerminalStart.Started(it) }
    val started = when (val start = launcher.start(launch)) {
      is ShellStart.Failed -> return TerminalStart.Failed(start.message)
      is ShellStart.Started -> start.process
    }
    return try {
      val attached = attach(started, settings)
      process = started
      view = attached
      started.onExit { exited() }
      // A shell that is already dead completes the exit future at once, so the session may have closed itself just now.
      if (closing.get()) TerminalStart.Closed else TerminalStart.Started(attached)
    } catch (failure: Exception) {
      started.destroy()
      TerminalStart.Failed(failure.message ?: failure.toString())
    } catch (failure: LinkageError) {
      started.destroy()
      TerminalStart.Failed(failure.message ?: failure.toString())
    }
  }

  /** Ends the shell and its view once; a closed session never opens again. */
  @Synchronized
  fun close() {
    if (!closing.compareAndSet(false, true)) return
    end()
  }

  // A close that raced this exit set `closing` first and ends the session itself, so it is not reported as an own exit.
  // This runs on the process reaper thread, so the Swing widget is closed on the event thread instead.
  private fun exited() {
    if (!closing.compareAndSet(false, true)) return
    try {
      synchronized(this) { end { SwingUtilities.invokeLater(it::close) } }
    } finally {
      onExit()
    }
  }

  private fun end(closeView: (TerminalView) -> Unit = TerminalView::close) {
    try {
      process?.destroy()
    } finally {
      try {
        view?.let(closeView)
      } finally {
        process = null
        view = null
      }
    }
  }
}
