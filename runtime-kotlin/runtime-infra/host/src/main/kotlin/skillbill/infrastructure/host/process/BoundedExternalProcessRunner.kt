package skillbill.infrastructure.host.process

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class BoundedExternalProcessRequest(
  val argv: List<String>,
  val workingDirectory: Path? = null,
  val environment: Map<String, String>? = null,
  val mergeEnvironment: Map<String, String> = emptyMap(),
  val clearEnvironment: Boolean = false,
  val stdin: ByteArray? = null,
  val mergeStderr: Boolean = true,
  val deadlineSeconds: Long,
  val output: BoundedExternalProcessOutput = BoundedExternalProcessOutput.Captured(),
)

data class BoundedExternalProcessResult(
  val exitCode: Int,
  val output: String = "",
  val timedOut: Boolean = false,
  val launchFailure: Boolean = false,
  val readFailure: IOException? = null,
)

object BoundedExternalProcessRunner {
  fun run(request: BoundedExternalProcessRequest): BoundedExternalProcessResult {
    val session = BoundedExternalProcessSession(request)
    return session.run()
  }
}

private class BoundedExternalProcessSession(
  private val request: BoundedExternalProcessRequest,
) {
  private val redirectFile = (request.output as? BoundedExternalProcessOutput.RedirectToFile)?.path
  private val deadlineSeconds = request.deadlineSeconds.coerceAtLeast(1L)
  private val operationDeadlineNanos =
    System.nanoTime() + TimeUnit.SECONDS.toNanos(deadlineSeconds)
  private val ownedDescendants = linkedSetOf<ProcessHandle>()
  private val output = StringBuilder()
  private val truncated = AtomicBoolean(false)
  private val stopRequested = AtomicBoolean(false)
  private val readFailure = AtomicReference<IOException?>()
  private var process: Process? = null
  private var outputThread: Thread? = null
  private var inputThread: Thread? = null
  private var timedOut = false
  private var exitCode = 1
  private var primaryFailure: Throwable? = null
  private var cleanupFailure: Throwable? = null

  fun run(): BoundedExternalProcessResult {
    try {
      if (!startProcess()) {
        return launchFailureResult()
      }
      awaitProcess()
      settleOutput()
    } catch (interrupted: InterruptedException) {
      primaryFailure = primaryFailure ?: interrupted
      destroyForInterrupted(interrupted)
      throw interrupted
    } finally {
      cleanup()
    }
    return buildResult()
  }

  private fun startProcess(): Boolean {
    val started =
      runCatching {
        ProcessBuilder(request.argv).apply {
          request.workingDirectory?.let { directory(it.toFile()) }
          if (request.clearEnvironment) {
            environment().clear()
          }
          request.environment?.let { environment().putAll(it) }
          if (request.mergeEnvironment.isNotEmpty()) {
            environment().putAll(request.mergeEnvironment)
          }
          if (request.mergeStderr) {
            redirectErrorStream(true)
          } else {
            redirectError(ProcessBuilder.Redirect.DISCARD)
          }
          redirectFile?.let { redirectOutput(it.toFile()) }
        }.start()
      }.getOrElse { failure ->
        primaryFailure = failure
        return false
      }
    process = started
    if (redirectFile == null) {
      startOutputThread(started)
    }
    startInput(started)
    return true
  }

  private fun launchFailureResult(): BoundedExternalProcessResult {
    val message = primaryFailure?.message.orEmpty().ifBlank { primaryFailure?.javaClass?.simpleName.orEmpty() }
    return BoundedExternalProcessResult(
      exitCode = 1,
      output = "Failed to launch process: $message",
      launchFailure = true,
      readFailure = primaryFailure as? IOException,
    )
  }

  private fun startOutputThread(process: Process) {
    outputThread =
      thread(start = true, name = "skill-bill-bounded-process-output") {
        drainOutput(process)
      }
  }

  private fun drainOutput(process: Process) {
    when (val mode = request.output) {
      is BoundedExternalProcessOutput.Captured ->
        try {
          truncated.set(readCappedOutput(process.inputStream, mode.capBytes, output))
        } catch (error: IOException) {
          readFailure.compareAndSet(null, error)
        }
      is BoundedExternalProcessOutput.Lines ->
        try {
          if (readBoundedLines(process.inputStream, mode)) {
            stopRequested.set(true)
          }
        } catch (error: IOException) {
          if (!mode.shouldStop()) {
            readFailure.compareAndSet(null, error)
          }
        }
      is BoundedExternalProcessOutput.RedirectToFile -> Unit
    }
  }

  private fun startInput(process: Process) {
    val stdin = request.stdin
    if (stdin == null) {
      runCatching { process.outputStream.close() }.onFailure(::recordCleanupFailure)
      return
    }
    inputThread =
      thread(start = true, name = "skill-bill-bounded-process-input") {
        try {
          process.outputStream.use { stream -> stream.write(stdin) }
        } catch (error: IOException) {
          readFailure.compareAndSet(null, error)
        }
      }
  }

  private fun awaitProcess() {
    val active = process ?: return
    while (active.isAlive) {
      captureOwnedDescendants(active)
      val remainingWaitNanos = operationDeadlineNanos - System.nanoTime()
      if (remainingWaitNanos <= 0L) {
        timedOut = true
        break
      }
      active.waitFor(
        minOf(remainingWaitNanos, TimeUnit.MILLISECONDS.toNanos(PROCESS_POLL_MILLIS)),
        TimeUnit.NANOSECONDS,
      )
      if (stopRequested.get()) {
        attemptCleanup { destroyProcessTree(active) }
      }
    }
    if (!timedOut && !active.isAlive) {
      exitCode = active.exitValue()
      settleOwnedDescendants()
    }
    if (timedOut) {
      attemptCleanup { destroyProcessTree(active) }
    }
  }

  private fun settleOutput() {
    val active = process ?: return
    val outputDeadlineNanos =
      if (timedOut) {
        System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_BUDGET_SECONDS)
      } else {
        minOf(
          operationDeadlineNanos,
          System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_BUDGET_SECONDS),
        )
      }
    inputThread?.let { writerThread ->
      if (!joinWithDeadline(writerThread, outputDeadlineNanos)) {
        readFailure.compareAndSet(null, IOException("process input delivery did not settle before deadline"))
      }
    }
    outputThread?.let { drainThread ->
      if (!closeInputAndJoin(active, drainThread, outputDeadlineNanos)) {
        readFailure.compareAndSet(null, IOException("process output capture did not settle before deadline"))
      }
    }
    if (Thread.currentThread().isInterrupted) {
      val interrupted = InterruptedException()
      primaryFailure = interrupted
      destroyForInterrupted(interrupted)
      throw interrupted
    }
  }

  private fun cleanup() {
    val active = process ?: return
    val cleanupDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_BUDGET_SECONDS)
    attemptCleanup { active.outputStream.close() }
    inputThread?.let { writerThread ->
      attemptCleanup { joinWithDeadline(writerThread, cleanupDeadlineNanos) }
    }
    outputThread?.let { drainThread ->
      attemptCleanup { closeInputAndJoin(active, drainThread, cleanupDeadlineNanos) }
    }
    attemptCleanup { destroyProcessTree(active) }
    attemptCleanup { active.inputStream.close() }
    attemptCleanup { active.outputStream.close() }
    runCatching {
      if (active.isAlive) {
        active.destroyForcibly()
        active.waitFor(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      }
    }.onFailure(::recordCleanupFailure)
  }

  private fun buildResult(): BoundedExternalProcessResult {
    val fileOutput =
      redirectFile?.let { path ->
        if (!Files.exists(path)) "" else Files.readString(path)
      }
    val captured =
      buildString {
        if (fileOutput != null) {
          append(fileOutput)
        } else {
          append(output)
          if (truncated.get()) append(INSTALLER_OUTPUT_TRUNCATION_SENTINEL)
        }
      }
    if (timedOut) {
      return BoundedExternalProcessResult(
        exitCode = TIMEOUT_EXIT_CODE,
        output = captured.ifBlank { readFailure.get()?.message.orEmpty() },
        timedOut = true,
        readFailure = readFailure.get(),
      )
    }
    readFailure.get()?.let { failure ->
      return BoundedExternalProcessResult(
        exitCode = if (exitCode == 0) 1 else exitCode,
        output = captured.ifBlank { failure.message.orEmpty() },
        readFailure = failure,
      )
    }
    return BoundedExternalProcessResult(exitCode = exitCode, output = captured)
  }

  private fun captureOwnedDescendants(process: Process) {
    runCatching {
      ownedDescendants += process.toHandle().descendants().toList()
    }.onFailure(::recordCleanupFailure)
  }

  private fun settleOwnedDescendants() {
    val deadlineNanos =
      minOf(
        operationDeadlineNanos,
        System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_BUDGET_SECONDS),
      )
    while (ownedDescendants.any { it.isAlive } && System.nanoTime() < deadlineNanos) {
      Thread.sleep(PROCESS_POLL_MILLIS)
    }
    if (ownedDescendants.any { it.isAlive }) {
      readFailure.compareAndSet(null, IOException("process exited with an owned descendant still running"))
    }
  }

  private fun destroyProcessTree(process: Process) {
    destroyOwnedProcessTree(process, ownedDescendants)
  }

  private fun destroyForInterrupted(interrupted: InterruptedException) {
    process?.let { active -> runCatching { destroyProcessTree(active) }.onFailure(interrupted::addSuppressed) }
  }

  private fun attemptCleanup(action: () -> Unit) {
    runCatching(action).exceptionOrNull()?.let(::recordCleanupFailure)
  }

  private fun recordCleanupFailure(failure: Throwable) {
    cleanupFailure = cleanupFailure?.also { existing -> existing.addSuppressed(failure) } ?: failure
  }
}

private const val TIMEOUT_EXIT_CODE = 124
