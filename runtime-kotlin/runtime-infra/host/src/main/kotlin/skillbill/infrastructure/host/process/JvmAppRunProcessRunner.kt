package skillbill.infrastructure.host.process

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.apprun.AppRunProcess
import skillbill.ports.apprun.AppRunProcessPort
import skillbill.ports.apprun.model.AppRunProcessExit
import skillbill.ports.apprun.model.AppRunProcessSpec
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Inject
class JvmAppRunProcessRunner(private val diagnostics: RuntimeDiagnostics) : AppRunProcessPort {
  override fun start(
    spec: AppRunProcessSpec,
    onLine: (String) -> Unit,
  ): AppRunProcess {
    val process =
      try {
        ProcessBuilder(spec.command).directory(spec.workingDirectory.toFile()).redirectErrorStream(true).apply {
          environment().putAll(spec.environment)
        }.start()
      } catch (failure: IOException) {
        throw IllegalStateException("Could not start ${spec.command.joinToString(" ")}: ${failure.message}", failure)
      }
    try {
      process.outputStream.close()
    } catch (failure: IOException) {
      destroyOwnedProcessTree(process)
      throw IllegalStateException("Could not start ${spec.command.joinToString(" ")}: ${failure.message}", failure)
    }
    val stopped = AtomicBoolean(false)
    val readerFailure = AtomicReference<Throwable?>()
    val reader =
      Thread({
        catchingNonCancellation {
          process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> lines.forEach(onLine) }
        }.onFailure { failure ->
          readerFailure.set(failure)
          destroyOwnedProcessTree(process)
        }
      }, "app-run-output-reader").apply {
        isDaemon = true
        start()
      }
    return object : AppRunProcess {
      override fun awaitExit(): AppRunProcessExit {
        val code =
          try {
            process.waitFor()
          } catch (failure: InterruptedException) {
            destroyOwnedProcessTree(process)
            Thread.currentThread().interrupt()
            throw failure
          }
        if (!joinWithDeadline(reader, System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_BUDGET_SECONDS))) {
          diagnostics.warning("seam=app-run.process outcome=reader_unjoined command=${spec.command.firstOrNull()}")
        }
        readerFailure.get()?.let { throw IllegalStateException("Could not read app-run output: ${it.message}", it) }
        return AppRunProcessExit(code, stopped.get())
      }

      override fun stop() {
        stopped.set(true)
        val descendants = process.toHandle().descendants().use { it.toList().toSet() }
        (descendants + process.toHandle()).forEach { it.destroy() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_GRACE_SECONDS)
        (descendants + process.toHandle()).forEach { handle ->
          val remaining = deadline - System.nanoTime()
          if (remaining > 0) {
            try {
              handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
            } catch (_: ExecutionException) {
            } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
              destroyOwnedProcessTree(process, descendants)
              return
            }
          }
        }
        if (process.isAlive || descendants.any { it.isAlive }) destroyOwnedProcessTree(process, descendants)
      }
    }
  }

  private companion object {
    const val STOP_GRACE_SECONDS = 3L
  }
}
