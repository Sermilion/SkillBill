package skillbill.infrastructure.launcher.process.launch

import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.host.process.destroyOwnedProcessTree
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProcessSample
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException

internal data class ProcessRunReleaseSnapshot(
  val interrupted: Boolean,
  val outputCaptureIncomplete: Boolean,
  val processTreeExited: Boolean,
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
    catchingNonCancellation { tree.refresh() }
      .onFailure { failure -> degradation.recordCleanupFailure("process_tree_discovery", failure) }
    closeStream(process.outputStream, "stdin_stream_close")
    terminateIfNeeded(waitResult?.getOrNull(), cleanupState)
    val stdoutIncomplete = joinDrain(stdout, "stdout_drain_join", cleanupState)
    val stderrIncomplete = joinDrain(stderr, "stderr_drain_join", cleanupState)
    closeStream(process.inputStream, "stdout_stream_close")
    closeStream(process.errorStream, "stderr_stream_close")
    val processTreeExited = recordProcessCleanup()
    val snapshot =
      ProcessRunReleaseSnapshot(
        interrupted = cleanupState.interrupted,
        processTreeExited = processTreeExited,
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
    if (wait?.sessionSettled == true && !state.interrupted && awaitTreeExit(state)) return
    if (!treeHasExited("process_tree_exit_check")) {
      catchingNonCancellation { tree.signalForcibly() }
        .onFailure { failure -> degradation.recordCleanupFailure("process_destroy_wait", failure) }
    }
    val exited = awaitTreeExit(state)
    if (!exited) {
      degradation.recordCleanupFailure(
        "process_destroy_wait",
        IllegalStateException("owned child process tree remained alive after forced termination"),
      )
    }
  }

  private fun awaitTreeExit(state: CleanupState): Boolean =
    try {
      catchingNonCancellation { tree.awaitExit(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        .onFailure { failure -> degradation.recordCleanupFailure("process_destroy_wait", failure) }
        .getOrDefault(false)
    } catch (interrupted: InterruptedException) {
      degradation.recordCleanupFailure("process_destroy_wait", interrupted)
      state.interrupted = true
      false
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

  private fun recordProcessCleanup(): Boolean {
    val exited = treeHasExited("process_cleanup_exit_check")
    if (!exited) {
      degradation.recordCleanupFailure(
        "process_cleanup",
        IllegalStateException("owned child process tree remained alive after cleanup"),
      )
    } else {
      liveProcesses.remove(tree)
    }
    return exited
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

internal fun interface ProcessSnapshot {
  fun sample(handle: ProcessHandle): AgentRunProcessSample?
}

/** Provides a temporary process sampler to command observation while a process is running. */
internal interface BuildCompletionSampleBinding {
  /** Binds [sample] until the returned handle closes. */
  fun bindCompletionSamples(sample: () -> List<AgentRunProcessSample>): AutoCloseable
}

/** Receives one notification after an output drain delivers its final decoded text. */
internal interface AgentRunOutputEndObserver {
  /** Reports that [stream] has no more output. */
  fun outputEnded(stream: AgentRunOutputStream)
}

internal object JdkProcessSnapshot : ProcessSnapshot {
  override fun sample(handle: ProcessHandle): AgentRunProcessSample? {
    if (!handle.isAlive) return null
    val info = handle.info()
    return AgentRunProcessSample(
      pid = handle.pid(),
      parentPid = handle.parent().map(ProcessHandle::pid).orElse(null),
      command = info.command().orElse(null),
      arguments = info.arguments().map { it.toList() }.orElse(emptyList()),
      startedAt = info.startInstant().orElse(null),
    )
  }
}

internal class AgentRunProcessTree(
  val process: Process,
  private val snapshot: ProcessSnapshot = JdkProcessSnapshot,
) {
  private val lock = Any()
  private val descendants = linkedSetOf<ProcessHandle>()
  private var observationComplete = true

  fun refresh(): Set<ProcessHandle> =
    synchronized(lock) {
      observationComplete = false
      val covered = process.toHandle().descendants().use { stream -> stream.toList() }.toMutableSet()
      descendants.forEach { handle ->
        if (handle !in covered && handle.isAlive) {
          handle.descendants().use { stream -> covered.addAll(stream.toList()) }
        }
      }
      descendants.addAll(covered)
      observationComplete = true
      descendants.toSet()
    }

  fun members(): Set<ProcessHandle> = synchronized(lock) { descendants.toSet() }

  fun samples(): List<AgentRunProcessSample> = members().mapNotNull(snapshot::sample)

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
