package dev.skillbill.tray.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TtyConnector
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** A started shell on a PTY. [destroy] is idempotent. */
interface TerminalProcess {
  val connector: TtyConnector

  fun destroy()
}

fun interface TerminalProcessFactory {
  fun start(launch: TerminalLaunch): TerminalProcess
}

object PtyTerminalProcessFactory : TerminalProcessFactory {
  override fun start(launch: TerminalLaunch): TerminalProcess {
    val process = PtyProcessBuilder(launch.command.toTypedArray())
      .setDirectory(launch.workingDirectory)
      .setEnvironment(launch.environment)
      .setInitialColumns(80)
      .setInitialRows(24)
      .start()
    return PtyTerminalProcess(process, PtyConnector(process, File(launch.command.first()).name))
  }
}

private class PtyTerminalProcess(private val process: PtyProcess, override val connector: TtyConnector) : TerminalProcess {
  private val destroyed = AtomicBoolean(false)

  override fun destroy() {
    if (!destroyed.compareAndSet(false, true)) return
    try {
      connector.close()
    } catch (_: IOException) {
      // The PTY is already gone; the process is destroyed below either way.
    }
    process.destroy()
  }
}

private class PtyConnector(private val process: PtyProcess, private val shellName: String) :
  ProcessTtyConnector(process, StandardCharsets.UTF_8) {
  override fun resize(termSize: TermSize) {
    if (isConnected) process.winSize = WinSize(termSize.columns, termSize.rows)
  }

  override fun getName(): String = shellName

  override fun isConnected(): Boolean = process.isAlive
}
