package skillbill.infrastructure.launcher.process.launch

import skillbill.infrastructure.launcher.process.waitloop.ProcessWait
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.io.Closeable
import java.util.concurrent.TimeUnit

internal data class ProcessRunReleaseSnapshot(
  val interrupted: Boolean,
  val outputCaptureIncomplete: Boolean,
  val stdoutCapture: Utf8DrainCapture,
  val stderrCapture: Utf8DrainCapture,
)

internal class ProcessRunLifetime(
  private val process: Process,
  private val liveProcesses: MutableSet<Process>,
  private val stdout: Utf8Drain,
  private val stderr: Utf8Drain,
  private val degradation: ProcessRunDegradationRecorder,
) {
  @Volatile
  private var released = false
  private var releaseSnapshot: ProcessRunReleaseSnapshot? = null

  init {
    liveProcesses.add(process)
  }

  fun release(waitResult: Result<ProcessWait>?): ProcessRunReleaseSnapshot {
    if (released) return requireNotNull(releaseSnapshot)
    released = true
    val cleanupState =
      CleanupState(
        interrupted = waitResult?.exceptionOrNull() is InterruptedException || Thread.currentThread().isInterrupted,
      )
    terminateIfNeeded(waitResult?.getOrNull(), cleanupState)
    closeStream(process.outputStream, "stdin_stream_close")
    val stdoutIncomplete = joinDrain(stdout, "stdout_drain_join", cleanupState)
    val stderrIncomplete = joinDrain(stderr, "stderr_drain_join", cleanupState)
    closeStream(process.inputStream, "stdout_stream_close")
    closeStream(process.errorStream, "stderr_stream_close")
    recordProcessCleanup()
    val snapshot =
      ProcessRunReleaseSnapshot(
        interrupted = cleanupState.interrupted,
        outputCaptureIncomplete =
          stdoutIncomplete || stderrIncomplete ||
            stdout.workerFailure != null || stderr.workerFailure != null,
        stdoutCapture = stdout.capture(),
        stderrCapture = stderr.capture(),
      )
    releaseSnapshot = snapshot
    if (cleanupState.interrupted) {
      Thread.currentThread().interrupt()
    }
    return snapshot
  }

  private data class CleanupState(var interrupted: Boolean)

  private fun terminateIfNeeded(
    wait: ProcessWait?,
    state: CleanupState,
  ) {
    if (wait?.finished == true) return
    runCatching {
      process.destroyForcibly()
      val exited = process.waitFor(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      if (!exited && process.isAlive) {
        degradation.recordCleanupFailure(
          "process_destroy_wait",
          IllegalStateException("child process remained alive after forced termination"),
        )
      }
    }.onFailure { failure ->
      degradation.recordCleanupFailure("process_destroy_wait", failure)
      if (failure is InterruptedException) state.interrupted = true
    }
  }

  private fun closeStream(
    stream: Closeable,
    seam: String,
  ) {
    runCatching { stream.close() }
      .onFailure { failure -> degradation.recordCleanupFailure(seam, failure) }
  }

  private fun joinDrain(
    drain: Utf8Drain,
    seam: String,
    state: CleanupState,
  ): Boolean =
    runCatching { drain.joinAndFreeze() }
      .onFailure { failure ->
        degradation.recordCleanupFailure(seam, failure)
        if (failure is InterruptedException) state.interrupted = true
      }
      .getOrDefault(true)

  private fun recordProcessCleanup() {
    if (process.isAlive) {
      degradation.recordCleanupFailure(
        "process_cleanup",
        IllegalStateException("child process remained alive after cleanup"),
      )
    } else {
      liveProcesses.remove(process)
    }
  }

  fun cachedRelease(): ProcessRunReleaseSnapshot =
    requireNotNull(releaseSnapshot) { "process lifetime was not released" }

  fun terminalOutcome(
    snapshot: ProcessRunReleaseSnapshot,
    wait: ProcessWait?,
  ): GoalProgressOutcome =
    when {
      snapshot.interrupted -> GoalProgressOutcome.CANCELLED
      wait?.finished == true -> GoalProgressOutcome.SUCCEEDED
      else -> GoalProgressOutcome.TIMED_OUT
    }
}
