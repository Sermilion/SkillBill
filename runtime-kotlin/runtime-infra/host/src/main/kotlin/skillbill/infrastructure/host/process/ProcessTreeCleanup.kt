package skillbill.infrastructure.host.process

import java.util.concurrent.TimeUnit

const val PROCESS_CLEANUP_BUDGET_SECONDS = 5L
const val PROCESS_POLL_MILLIS = 50L
const val DESTROY_WAIT_TIMEOUT_MILLIS = 1_000L

fun destroyOwnedProcessTree(
  process: Process,
  knownDescendants: Set<ProcessHandle> = emptySet(),
) {
  var failure: Throwable? = null

  fun attempt(action: () -> Unit) {
    val error = runCatching(action).exceptionOrNull() ?: return
    failure = failure?.also { existing -> existing.addSuppressed(error) } ?: error
  }
  val handle =
    runCatching { process.toHandle() }
      .onFailure { error -> failure = error }
      .getOrNull()
  handle?.let { ownedProcess ->
    val descendants = linkedSetOf<ProcessHandle>()
    descendants += knownDescendants
    if (ownedProcess.isAlive) {
      ownedProcess.descendants().use { descendants += it.toList() }
    }
    descendants.forEach { descendant ->
      attempt { descendant.destroyForcibly() }
    }
    attempt {
      ownedProcess.destroyForcibly()
    }
  }
  attempt { if (process.isAlive) process.destroyForcibly() }
  failure?.let { throw it }
}

fun closeInputAndJoin(
  process: Process,
  outputThread: Thread,
  joinDeadlineNanos: Long,
): Boolean {
  if (joinWithDeadline(outputThread, joinDeadlineNanos)) {
    return true
  }
  runCatching { process.inputStream.close() }
  joinWithDeadline(outputThread, joinDeadlineNanos)
  return false
}

fun joinWithDeadline(
  thread: Thread,
  deadlineNanos: Long,
): Boolean {
  val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
  if (remainingMillis <= 0L) {
    return false
  }
  thread.join(remainingMillis)
  return !thread.isAlive
}
