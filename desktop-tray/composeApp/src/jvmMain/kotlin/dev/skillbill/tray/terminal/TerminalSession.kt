package dev.skillbill.tray.terminal

import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

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
 * One window's terminal: at most one process and one view, built on the first [open] and kept until [close]. It lives outside
 * composition, so hiding the window or closing the pane never ends the shell.
 */
class TerminalSession(
  private val processFactory: TerminalProcessFactory,
  private val attach: (TerminalProcess, SettingsProvider) -> TerminalView,
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
      TerminalStart.Started(attached)
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
    try {
      process?.destroy()
    } finally {
      view?.close()
    }
  }
}
