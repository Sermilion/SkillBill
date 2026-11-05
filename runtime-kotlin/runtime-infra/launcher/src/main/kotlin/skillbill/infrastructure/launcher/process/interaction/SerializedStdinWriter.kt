package skillbill.infrastructure.launcher.process.interaction

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class SerializedStdinWriter(private val output: OutputStream) {
  private val executor: ExecutorService =
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "skillbill-agent-run-stdin").apply { isDaemon = true }
    }
  private val closed = AtomicBoolean(false)

  fun write(frame: ByteArray): Boolean {
    if (closed.get()) return false
    val pending =
      try {
        executor.submit<Unit> {
          output.write(frame)
          output.flush()
        }
      } catch (_: RejectedExecutionException) {
        return false
      }
    return try {
      pending.get(WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      true
    } catch (_: TimeoutException) {
      pending.cancel(true)
      false
    } catch (_: ExecutionException) {
      false
    } catch (_: InterruptedException) {
      pending.cancel(true)
      Thread.currentThread().interrupt()
      false
    }
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    try {
      executor.execute {
        try {
          output.close()
        } catch (_: IOException) {
        }
      }
    } catch (_: RejectedExecutionException) {
    }
    executor.shutdown()
  }

  private companion object {
    const val WRITE_TIMEOUT_MILLIS = 5_000L
  }
}
