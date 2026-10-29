package dev.skillbill.desktop.feature.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TtyConnector
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import dev.skillbill.desktop.core.common.di.AppScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** A started shell on a PTY. [destroy] is idempotent. */
interface ShellProcess {
  val connector: TtyConnector

  fun destroy()

  /** Runs [listener] once when the shell ends, including after [destroy], on the process reaper thread. */
  fun onExit(listener: () -> Unit)
}

sealed interface ShellStart {
  data class Started(val process: ShellProcess) : ShellStart

  data class Failed(val message: String) : ShellStart
}

fun interface ShellLauncher {
  fun start(launch: TerminalLaunch): ShellStart
}

/**
 * The app's only process start, and the only file that names a process type. A missing pty4j native library surfaces as a
 * [LinkageError] or as the [RuntimeException] pty4j wraps it in ("Cannot load native pty executor library"), so it is a start
 * failure, not a crash.
 */
@Inject
@ContributesBinding(AppScope::class)
class PtyShellLauncher : ShellLauncher {
  override fun start(launch: TerminalLaunch): ShellStart {
    if (!Files.isDirectory(launch.workingDirectory)) return ShellStart.Failed("Directory not found: ${launch.workingDirectory}")
    return try {
      val process = PtyProcessBuilder(launch.command.toTypedArray())
        .setDirectory(launch.workingDirectory.toString())
        .setEnvironment(launch.environment)
        .setInitialColumns(80)
        .setInitialRows(24)
        .start()
      ShellStart.Started(PtyShellProcess(process, PtyConnector(process, File(launch.command.first()).name)))
    } catch (failure: IOException) {
      ShellStart.Failed(failure.message ?: failure.toString())
    } catch (failure: LinkageError) {
      ShellStart.Failed(failure.message ?: failure.toString())
    } catch (failure: RuntimeException) {
      ShellStart.Failed(failure.message ?: failure.toString())
    }
  }
}

private val shutdownHookInstalled = AtomicBoolean(false)

/** Installs the one JVM shutdown hook that ends every open shell when the app exits; later calls do nothing. */
fun installShutdownHook(closeAll: () -> Unit) {
  if (shutdownHookInstalled.compareAndSet(false, true)) Runtime.getRuntime().addShutdownHook(Thread(closeAll, "terminal-shutdown"))
}

private class PtyShellProcess(private val process: PtyProcess, override val connector: TtyConnector) : ShellProcess {
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

  override fun onExit(listener: () -> Unit) {
    process.onExit().thenRun { listener() }
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
