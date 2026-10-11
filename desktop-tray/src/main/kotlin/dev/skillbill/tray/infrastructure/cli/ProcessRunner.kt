package dev.skillbill.tray.infrastructure.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

sealed interface ProcessResult {
  data class Success(val output: String) : ProcessResult
  data object Busy : ProcessResult
  data object Timeout : ProcessResult
  data object OutputLimit : ProcessResult
  data object Failed : ProcessResult
  data object Rejected : ProcessResult
}

class ProcessRunner(
  private val maxStdoutBytes: Int = 4 * 1024 * 1024,
  private val timeout: Duration = Duration.ofSeconds(10),
) {
  private val slot = Semaphore(1)

  suspend fun run(command: List<String>, environment: Map<String, String> = emptyMap(), processGate: (((List<String>) -> Process) -> Process?)? = null): ProcessResult = withContext(Dispatchers.IO) {
    if (!slot.tryAcquire()) return@withContext ProcessResult.Busy
    val createProcess: (List<String>) -> Process = { arguments -> ProcessBuilder(arguments).also { it.environment().putAll(environment) }.start() }
    val process = try {
      if (processGate != null) processGate.invoke(createProcess) else createProcess(command)
    } catch (_: Exception) {
      RuntimeDiagnostics.record("process_start_failed")
      slot.release()
      return@withContext ProcessResult.Failed
    }
    if (process == null) {
      slot.release()
      return@withContext ProcessResult.Rejected
    }
    val stdout = BoundedCollector(maxStdoutBytes, "stdout_limit")
    val stderr = BoundedCollector(8192, "stderr_limit")
    val stdoutThread = thread(isDaemon = true, name = "skillbill-cli-stdout") { stdout.drain(process.inputStream) }
    val stderrThread = thread(isDaemon = true, name = "skillbill-cli-stderr") { stderr.drain(process.errorStream) }
    val completion = CompletableFuture<ProcessResult>()
    thread(isDaemon = true, name = "skillbill-cli-reaper") {
      process.waitFor()
      stdoutThread.join()
      stderrThread.join()
      val result = when {
        stdout.exceeded || stderr.exceeded -> ProcessResult.OutputLimit
        stdout.failed || stderr.failed -> ProcessResult.Failed
        process.exitValue() != 0 -> ProcessResult.Failed.also { RuntimeDiagnostics.record("command_failed") }
        else -> ProcessResult.Success(stdout.text())
      }
      slot.release()
      completion.complete(result)
    }
    try {
      completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      RuntimeDiagnostics.record("request_timeout")
      ProcessResult.Timeout
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      RuntimeDiagnostics.record("request_interrupted")
      ProcessResult.Timeout
    }
  }
}

private class BoundedCollector(private val limit: Int, private val limitCategory: String) {
  private val bytes = ByteArrayOutputStream(minOf(limit, 8192))
  @Volatile var exceeded = false
    private set
  @Volatile var failed = false
    private set

  fun drain(input: java.io.InputStream) {
    try {
      input.use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
          val count = stream.read(buffer)
          if (count < 0) return
          val remaining = limit - bytes.size()
          if (count > remaining && !exceeded) {
            exceeded = true
            RuntimeDiagnostics.record(limitCategory)
          }
          if (remaining > 0) bytes.write(buffer, 0, minOf(count, remaining))
        }
      }
    } catch (_: Exception) {
      failed = true
      RuntimeDiagnostics.record("stream_read_failed")
    }
  }

  @Synchronized fun text(): String = bytes.toString(StandardCharsets.UTF_8)
}
