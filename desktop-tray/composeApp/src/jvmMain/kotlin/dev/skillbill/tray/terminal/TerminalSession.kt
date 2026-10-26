package dev.skillbill.tray.terminal

import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** The Swing side of one terminal: the component Compose hosts and the way to end it. */
interface TerminalView {
  val component: JComponent

  fun close()
}

fun jediTermView(process: TerminalProcess, settings: SettingsProvider): TerminalView {
  val widget = JediTermWidget(80, 24, settings)
  try {
    widget.setTtyConnector(process.connector)
    widget.start()
  } catch (failure: Throwable) {
    widget.close()
    throw failure
  }
  return object : TerminalView {
    override val component: JComponent = widget

    override fun close() = widget.close()
  }
}

sealed interface TerminalStart {
  data class Started(val view: TerminalView) : TerminalStart

  data class Failed(val message: String) : TerminalStart

  data object Closed : TerminalStart
}

/**
 * One tab's terminal: at most one process and one view, built on the first [open] and kept until [close] or until the shell exits
 * on its own, which calls [onExit] once. It lives outside composition, so hiding the window or closing the pane never ends the
 * shell. [onExit] runs on the thread that saw the shell end, not the composition thread, and never for a [close].
 */
class TerminalSession(
  private val processFactory: TerminalProcessFactory,
  private val attach: (TerminalProcess, SettingsProvider) -> TerminalView,
  private val onExit: () -> Unit,
) {
  private val closing = AtomicBoolean(false)
  private var process: TerminalProcess? = null
  private var view: TerminalView? = null

  /**
   * Starts the shell on the first call; later calls return the same view whatever [launch] names. A failure can be retried.
   * A missing pty4j native library or JDK module surfaces as a [LinkageError], so it is a start failure too, not a crash.
   */
  @Synchronized
  fun open(launch: TerminalLaunch, settings: SettingsProvider): TerminalStart {
    if (closing.get()) return TerminalStart.Closed
    view?.let { return TerminalStart.Started(it) }
    val started = try {
      processFactory.start(launch)
    } catch (failure: Exception) {
      return failed(failure)
    } catch (failure: LinkageError) {
      return failed(failure)
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
      failed(failure)
    } catch (failure: LinkageError) {
      started.destroy()
      failed(failure)
    }
  }

  private fun failed(failure: Throwable) = TerminalStart.Failed(failure.message ?: failure.toString())

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

  // Drops the process and view once ended: the window scope's completion handler keeps this session until the window is
  // disposed, and must not keep a closed widget and its scrollback alive with it.
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
