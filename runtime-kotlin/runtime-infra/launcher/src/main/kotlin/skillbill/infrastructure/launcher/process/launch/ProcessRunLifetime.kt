package skillbill.infrastructure.launcher.process.launch

import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.host.process.destroyOwnedProcessTree
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException

internal data class ProcessRunReleaseSnapshot(
  val interrupted: Boolean,
  val outputCaptureIncomplete: Boolean,
  val stdoutCapture: Utf8DrainCapture,
  val stderrCapture: Utf8DrainCapture,
)

internal class ProcessRunLifetime(
  private val tree: AgentRunProcessTree,
  private val liveProcesses: MutableSet<AgentRunProcessTree>,
  private val stdout: Utf8Drain,
  private val stderr: Utf8Drain,
  private val degradation: ProcessRunDegradationRecorder,
) {
  @Volatile
  private var released = false
  private var releaseSnapshot: ProcessRunReleaseSnapshot? = null

  private val process: Process get() = tree.process

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
    catchingNonCancellation { tree.refresh() }
      .onFailure { failure -> degradation.recordCleanupFailure("process_tree_discovery", failure) }
    if (wait?.finished == true && treeHasExited("process_tree_exit_check")) return
    if (!treeHasExited("process_tree_exit_check")) {
      catchingNonCancellation { tree.signalForcibly() }
        .onFailure { failure -> degradation.recordCleanupFailure("process_destroy_wait", failure) }
    }
    val exited =
      try {
        catchingNonCancellation { tree.awaitExit(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
          .onFailure { failure -> degradation.recordCleanupFailure("process_destroy_wait", failure) }
          .getOrDefault(false)
      } catch (interrupted: InterruptedException) {
        degradation.recordCleanupFailure("process_destroy_wait", interrupted)
        state.interrupted = true
        false
      }
    if (!exited) {
      degradation.recordCleanupFailure(
        "process_destroy_wait",
        IllegalStateException("owned child process tree remained alive after forced termination"),
      )
    }
  }

  private fun treeHasExited(seam: String): Boolean =
    catchingNonCancellation { tree.confirmedExited() }
      .onFailure { failure -> degradation.recordCleanupFailure(seam, failure) }
      .getOrDefault(false)

  private fun closeStream(
    stream: Closeable,
    seam: String,
  ) {
    catchingNonCancellation { stream.close() }
      .onFailure { failure -> degradation.recordCleanupFailure(seam, failure) }
  }

  private fun joinDrain(
    drain: Utf8Drain,
    seam: String,
    state: CleanupState,
  ): Boolean =
    try {
      catchingNonCancellation { drain.joinAndFreeze() }
        .onFailure { failure -> degradation.recordCleanupFailure(seam, failure) }
        .getOrDefault(true)
    } catch (interrupted: InterruptedException) {
      degradation.recordCleanupFailure(seam, interrupted)
      state.interrupted = true
      true
    }

  private fun recordProcessCleanup() {
    if (!treeHasExited("process_cleanup_exit_check")) {
      degradation.recordCleanupFailure(
        "process_cleanup",
        IllegalStateException("owned child process tree remained alive after cleanup"),
      )
    } else {
      liveProcesses.remove(tree)
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

internal inline fun cleanupStep(
  onFailure: (Throwable) -> Unit = {},
  step: () -> Unit,
) {
  try {
    catchingNonCancellation(step).onFailure(onFailure)
  } catch (interrupted: InterruptedException) {
    onFailure(interrupted)
    Thread.currentThread().interrupt()
  }
}

internal class AgentRunProcessTree(val process: Process) {
  private val lock = Any()
  private val descendants = linkedSetOf<ProcessHandle>()
  private var observationComplete = true

  fun refresh(): Set<ProcessHandle> =
    synchronized(lock) {
      observationComplete = false
      val roots = listOf(process.toHandle()) + descendants.filter(ProcessHandle::isAlive)
      roots.forEach { root -> root.descendants().use { stream -> descendants.addAll(stream.toList()) } }
      observationComplete = true
      descendants.toSet()
    }

  fun members(): Set<ProcessHandle> = synchronized(lock) { descendants.toSet() }

  fun signalGracefully() {
    synchronized(lock) {
      descendants.forEach { it.destroy() }
      process.destroy()
    }
  }

  fun signalForcibly() {
    synchronized(lock) {
      val captured = descendants.toSet()
      try {
        catchingNonCancellation { destroyOwnedProcessTree(process, captured) }
          .onFailure { failure -> forceRemaining(captured, failure) }
          .getOrThrow()
      } catch (interrupted: InterruptedException) {
        forceRemaining(captured, interrupted)
        throw interrupted
      } catch (cancelled: CancellationException) {
        forceRemaining(captured, cancelled)
        throw cancelled
      }
    }
  }

  private fun forceRemaining(
    captured: Set<ProcessHandle>,
    failure: Throwable,
  ) {
    captured.forEach { descendant -> suppressCleanupFailure(failure) { descendant.destroyForcibly() } }
    suppressCleanupFailure(failure) { process.destroyForcibly() }
  }

  private inline fun suppressCleanupFailure(
    failure: Throwable,
    action: () -> Unit,
  ) {
    try {
      catchingNonCancellation(action).onFailure(failure::addSuppressed)
    } catch (interrupted: InterruptedException) {
      failure.addSuppressed(interrupted)
      Thread.currentThread().interrupt()
    } catch (cancelled: CancellationException) {
      failure.addSuppressed(cancelled)
    }
  }

  fun awaitExit(
    timeout: Long,
    unit: TimeUnit,
  ): Boolean {
    val handles = members() + process.toHandle()
    val live = handles.filter(ProcessHandle::isAlive)
    if (live.isEmpty()) return confirmedExited()
    return try {
      live.map(ProcessHandle::onExit)
        .fold(CompletableFuture.completedFuture<Void>(null)) { combined, exit ->
          combined.thenCombine(exit) { _, _ -> null }
        }.get(timeout, unit)
      confirmedExited()
    } catch (_: TimeoutException) {
      false
    }
  }

  fun confirmedExited(): Boolean =
    synchronized(lock) {
      observationComplete && (descendants + process.toHandle()).none(ProcessHandle::isAlive)
    }
}
