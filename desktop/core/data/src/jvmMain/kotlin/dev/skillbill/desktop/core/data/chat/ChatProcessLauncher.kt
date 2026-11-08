package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runInterruptible
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** One agent process: [environment] replaces the inherited one, and [stdin] is written once and closed. */
data class ChatCommand(
  val argv: List<String>,
  val workingDirectory: String,
  val environment: Map<String, String>,
  val stdin: String,
  val probeInvocation: List<String> = argv,
)

/** What a chat process produces: stdout [Line]s, a possible [StdoutEnded], then [Exited] or [StartFailed]. */
sealed interface ChatProcessOutput {
  data class Line(val text: String) : ChatProcessOutput

  data class StdoutEnded(val readFailure: String?, val stderrTail: String) : ChatProcessOutput

  data class Exited(val code: Int, val stderrTail: String) : ChatProcessOutput

  data class StartFailed(val message: String) : ChatProcessOutput
}

fun interface ChatProcessLauncher {
  /** A cold flow that starts the process on collection and destroys it when the collection ends early. */
  fun run(command: ChatCommand): Flow<ChatProcessOutput>
}

fun interface LiveChatProcessLauncher {
  /**
   * A cold flow that starts a process that lives across turns: it writes each line of [input] to stdin as it arrives, closes
   * stdin when [input] closes, and destroys the process with its descendants when the collection ends early. [ChatCommand.stdin]
   * is not used.
   */
  fun open(command: ChatCommand, input: ReceiveChannel<String>): Flow<ChatProcessOutput>

  fun openAcknowledged(command: ChatCommand, input: ReceiveChannel<ChatProcessInput>): Flow<ChatProcessOutput> = flow {
    throw IllegalStateException("This chat process launcher does not acknowledge completed stdin writes.")
  }
}

class ChatProcessInput internal constructor(val line: String) {
  internal val written = CompletableDeferred<Boolean>()
}

internal suspend fun Channel<ChatProcessInput>.writeAndAwait(line: String): Boolean {
  val pending = ChatProcessInput(line)
  return try {
    send(pending)
    pending.written.await()
  } catch (_: ClosedSendChannelException) {
    false
  }
}

internal fun Channel<ChatProcessInput>.writeAndAwaitBlocking(line: String): Boolean = runBlocking {
  withTimeoutOrNull(5_000) { writeAndAwait(line) } ?: false
}

sealed interface BoundedProcessStart {
  data class Started(val connection: BoundedProtocolConnection) : BoundedProcessStart
  data class Failed(val kind: Kind) : BoundedProcessStart

  enum class Kind { Busy, Deadline, StartFailure }
}

sealed interface BoundedProtocolInput {
  data class Line(val text: String) : BoundedProtocolInput
  data class Failed(val kind: Kind) : BoundedProtocolInput
  data object Exited : BoundedProtocolInput

  enum class Kind { LineTooLarge, ResponseTooLarge, QueueFull, InvalidUtf8, Transport }
}

interface BoundedProtocolConnection {
  suspend fun sendLine(line: String): Boolean
  suspend fun receive(): BoundedProtocolInput
  fun beginResponseBudget()
  fun endResponseBudget()
  suspend fun close()
}

fun interface BoundedProtocolLauncher {
  suspend fun start(command: ChatCommand): BoundedProcessStart
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProcessBoundedProtocolLauncher(private val diagnostics: Diagnostics) : BoundedProtocolLauncher {
  override suspend fun start(command: ChatCommand): BoundedProcessStart {
    if (!File(command.workingDirectory).isDirectory) return BoundedProcessStart.Failed(BoundedProcessStart.Kind.StartFailure)
    return UsageProcessStartup.start(command, diagnostics)
  }
}

/** The only chat file that names a process type. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProcessChatLauncher(private val diagnostics: Diagnostics) : ChatProcessLauncher {
  override fun run(command: ChatCommand): Flow<ChatProcessOutput> = runProcess(command, diagnostics) { process ->
    try {
      process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(command.stdin) }
    } catch (_: IOException) {
      // The process exited before reading its input; its exit code and stderr report why.
    }
  }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProcessLiveChatLauncher(private val diagnostics: Diagnostics) : LiveChatProcessLauncher {
  override fun open(command: ChatCommand, input: ReceiveChannel<String>): Flow<ChatProcessOutput> = flow {
    val acknowledged = Channel<ChatProcessInput>(Channel.UNLIMITED) { it.written.complete(false) }
    coroutineScope {
      val forwarding = launch {
        try {
          for (line in input) {
            val message = ChatProcessInput(line)
            acknowledged.send(message)
            message.written.await()
          }
        } finally {
          acknowledged.close()
        }
      }
      try {
        openAcknowledged(command, acknowledged).collect { emit(it) }
      } finally {
        forwarding.cancel()
        acknowledged.cancel()
      }
    }
  }

  override fun openAcknowledged(
    command: ChatCommand,
    input: ReceiveChannel<ChatProcessInput>,
  ): Flow<ChatProcessOutput> = runProcess(command, diagnostics) { process ->
    try {
      process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
        for (message in input) {
          var written = false
          try {
            runInterruptible {
              writer.write(message.line)
              writer.newLine()
              writer.flush()
            }
            written = true
          } catch (_: IOException) {
            written = false
          } finally {
            message.written.complete(written)
          }
          if (!written) break
        }
      }
    } catch (_: IOException) {
      // The process exited; its exit code and stderr end the stream.
    } finally {
      input.cancel()
      while (true) {
        val message = input.tryReceive().getOrNull() ?: break
        message.written.complete(false)
      }
    }
  }
}

internal suspend fun runChatProcessProbe(command: ChatCommand, argv: List<String>): ChatProcessProbeResult {
  val processBuilder = ProcessBuilder(argv).directory(File(command.workingDirectory))
  processBuilder.redirectErrorStream(true)
  processBuilder.environment().clear()
  processBuilder.environment().putAll(command.environment)
  val process = try {
    processBuilder.start()
  } catch (failure: IOException) {
    return ChatProcessProbeResult(-1, failure.message.orEmpty())
  }
  val output = StringBuilder()
  val drain = Thread({
    process.inputStream.bufferedReader().use { reader ->
      val buffer = CharArray(2048)
      while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        synchronized(output) { output.append(buffer, 0, count) }
      }
    }
  }, "chat-process-probe-output").apply {
    isDaemon = true
    start()
  }
  val job = currentCoroutineContext().job
  val cancellation = job.invokeOnCompletion { cause ->
    if (cause is kotlinx.coroutines.CancellationException) process.destroyForcibly()
  }
  return try {
    process.outputStream.close()
    val completed = process.waitFor(30, TimeUnit.SECONDS)
    if (!completed) {
      process.destroyForcibly()
      drain.join(1000)
      return ChatProcessProbeResult(-1, "")
    }
    drain.join(1000)
    currentCoroutineContext().ensureActive()
    ChatProcessProbeResult(process.exitValue(), synchronized(output) { output.toString() })
  } finally {
    cancellation.dispose()
    if (process.isAlive) process.destroyForcibly()
    drain.join(1000)
  }
}

internal data class ChatProcessProbeResult(val exitCode: Int, val stdout: String)

private fun stdoutLines(process: Process): ReceiveChannel<String> {
  val lines = Channel<String>(Channel.BUFFERED)
  thread(isDaemon = true, name = "chat-stdout") {
    try {
      process.inputStream.bufferedReader(Charsets.UTF_8).useLines { source ->
        for (line in source) if (lines.trySendBlocking(line).isFailure) break
      }
    } catch (failure: IOException) {
      lines.close(failure)
    } finally {
      lines.close()
    }
  }
  return lines
}

// Starts [command], runs [feed] to supply stdin, and streams stdout until the process exits.
private fun runProcess(command: ChatCommand, diagnostics: Diagnostics, feed: suspend (Process) -> Unit): Flow<ChatProcessOutput> =
  flow {
    if (!File(command.workingDirectory).isDirectory) {
      emit(ChatProcessOutput.StartFailed("The working directory ${command.workingDirectory} does not exist."))
      return@flow
    }
    val builder = ProcessBuilder(command.argv).directory(File(command.workingDirectory))
    builder.environment().clear()
    builder.environment().putAll(command.environment)
    val process = try {
      builder.start()
    } catch (failure: IOException) {
      emit(ChatProcessOutput.StartFailed(failure.message ?: failure.toString()))
      return@flow
    }
    ChatProcesses.live += process
    ChatProcesses.installShutdownHook()
    try {
      coroutineScope {
        // A blocked waitFor ignores cancellation; destroying the process ends the wait.
        val killer = launch {
          try {
            awaitCancellation()
          } finally {
            ChatProcesses.destroyTree(process, diagnostics)
          }
        }
        val stderr = StringBuilder()
        val drain = thread(isDaemon = true, name = "chat-stderr") {
          process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(1024)
            while (true) {
              val read = reader.read(buffer)
              if (read < 0) break
              synchronized(stderr) {
                stderr.append(buffer, 0, read)
                if (stderr.length > ChatProcesses.STDERR_TAIL) stderr.delete(0, stderr.length - ChatProcesses.STDERR_TAIL)
              }
            }
          }
        }
        val feeding = launch { feed(process) }
        val lines = stdoutLines(process)
        var stdoutFailure: IOException? = null
        try {
          while (true) {
            val line = lines.receiveCatching()
            if (line.isSuccess) {
              emit(ChatProcessOutput.Line(requireNotNull(line.getOrNull())))
            } else {
              stdoutFailure = line.exceptionOrNull() as? IOException
              break
            }
          }
        } finally {
          lines.cancel()
        }
        currentCoroutineContext().ensureActive()
        val stderrAtStdoutEnd = synchronized(stderr) { stderr.toString() }
        if (stdoutFailure != null) diagnostics.record("chat.stdout_read_failed")
        val readFailure = stdoutFailure?.let { (it.message ?: it.toString()).takeLast(1000) }
        emit(ChatProcessOutput.StdoutEnded(readFailure, stderrAtStdoutEnd))
        val code = runInterruptible { process.waitFor() }
        // A descendant can hold stderr open after claude exits; report the tail read so far.
        drain.join(ChatProcesses.STDERR_DRAIN_MS)
        emit(ChatProcessOutput.Exited(code, synchronized(stderr) { stderr.toString() }))
        feeding.cancel()
        killer.cancel()
      }
    } finally {
      ChatProcesses.destroyTree(process, diagnostics)
      ChatProcesses.live -= process
    }
  }.flowOn(Dispatchers.IO)

private object UsageProcessStartup {
  private const val START_DEADLINE_MS = 15_000L
  private val occupied = AtomicBoolean(false)

  suspend fun start(command: ChatCommand, diagnostics: Diagnostics): BoundedProcessStart {
    if (!occupied.compareAndSet(false, true)) return BoundedProcessStart.Failed(BoundedProcessStart.Kind.Busy)
    val result = CompletableDeferred<BoundedProcessOwnership?>()
    val slotReleased = AtomicBoolean(false)
    val releaseStartup = { if (slotReleased.compareAndSet(false, true)) occupied.set(false) }
    val handoff = AtomicInteger(PENDING)
    val decision = CountDownLatch(1)
    thread(isDaemon = true, name = "usage-process-start") {
      val created = try {
        val builder = ProcessBuilder(command.argv).directory(File(command.workingDirectory))
        builder.environment().clear()
        builder.environment().putAll(command.environment)
        builder.start()
      } catch (_: Exception) {
        null
      }
      val ownership = try {
        created?.let { process -> BoundedProcessOwnership(process, diagnostics, releaseStartup) }
      } catch (_: Exception) {
        diagnostics.record("usage.process_cleanup_failure")
        null
      }
      val registeredOwnership = try {
        ownership?.also(ChatProcesses::registerBounded)
      } catch (_: Exception) {
        diagnostics.record("usage.process_cleanup_failure")
        ownership?.let(ChatProcesses::destroyOwnedTree)
        null
      }
      result.complete(registeredOwnership)
      if (registeredOwnership == null) {
        releaseStartup()
        return@thread
      }
      val processOwnership = registeredOwnership
      while (true) {
        try {
          decision.await()
          break
        } catch (_: InterruptedException) {
          handoff.compareAndSet(PENDING, ABANDONED)
          break
        }
      }
      if (handoff.get() == ABANDONED) cleanupAbandoned(processOwnership)
    }
    return try {
      val awaited = withTimeoutOrNull(START_DEADLINE_MS) { StartupAwait(result.await()) }
      val ownership = awaited?.ownership
      if (awaited == null) {
        handoff.compareAndSet(PENDING, ABANDONED)
        decision.countDown()
        BoundedProcessStart.Failed(BoundedProcessStart.Kind.Deadline)
      } else if (ownership == null) {
        handoff.set(ABANDONED)
        decision.countDown()
        BoundedProcessStart.Failed(BoundedProcessStart.Kind.StartFailure)
      } else if (handoff.compareAndSet(PENDING, CLAIMED)) {
        decision.countDown()
        try {
          BoundedProcessStart.Started(BoundedProcessConnection(ownership))
        } catch (_: Exception) {
          cleanupAbandoned(ownership)
          BoundedProcessStart.Failed(BoundedProcessStart.Kind.StartFailure)
        }
      } else {
        decision.countDown()
        BoundedProcessStart.Failed(BoundedProcessStart.Kind.Deadline)
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      handoff.compareAndSet(PENDING, ABANDONED)
      decision.countDown()
      throw cancelled
    }
  }

  private data class StartupAwait(val ownership: BoundedProcessOwnership?)

  private fun cleanupAbandoned(ownership: BoundedProcessOwnership) = ChatProcesses.destroyOwnedTree(ownership)

  private const val PENDING = 0
  private const val CLAIMED = 1
  private const val ABANDONED = 2
}

private class BoundedProcessConnection(
  private val ownership: BoundedProcessOwnership,
) : BoundedProtocolConnection {
  private val process = ownership.process
  private val incoming = Channel<BoundedProtocolInput>(32)
  private val terminal = CompletableDeferred<BoundedProtocolInput>()
  private val writerLock = Mutex()
  private val framer = BoundedStdoutFramer()
  private val closed = AtomicBoolean(false)
  private val stderrTail = StringBuilder()
  private val writes = ArrayBlockingQueue<PendingWrite>(1)
  private val pendingWrite = AtomicReference<PendingWrite?>()
  private val writer = thread(start = false, isDaemon = true, name = "usage-protocol-stdin") { writeLines() }

  init {
    writer.start()
    thread(isDaemon = true, name = "usage-protocol-stdout") { readFrames(process.inputStream) }
    thread(isDaemon = true, name = "usage-protocol-stderr") { drainStderr(process.errorStream) }
    thread(isDaemon = true, name = "usage-process-exit") {
      try {
        process.waitFor()
        fail(BoundedProtocolInput.Exited)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        fail(BoundedProtocolInput.Exited)
      }
    }
  }

  override suspend fun sendLine(line: String): Boolean = writerLock.withLock {
    if (closed.get()) return@withLock false
    val write = PendingWrite(line, CompletableDeferred())
    pendingWrite.set(write)
    try {
      if (closed.get() || !writes.offer(write)) return@withLock false
      write.result.await()
    } catch (cancelled: CancellationException) {
      fail(BoundedProtocolInput.Exited)
      throw cancelled
    } finally {
      pendingWrite.compareAndSet(write, null)
    }
  }

  override suspend fun receive(): BoundedProtocolInput = select {
    terminal.onAwait { it }
    incoming.onReceiveCatching { it.getOrNull() ?: terminal.await() }
  }

  override fun beginResponseBudget() {
    framer.beginResponseBudget()
  }

  override fun endResponseBudget() {
    framer.endResponseBudget()
  }

  override suspend fun close() {
    fail(BoundedProtocolInput.Exited)
  }

  private fun writeLines() {
    try {
      while (!closed.get()) {
        val write = writes.take()
        if (closed.get()) break
        process.outputStream.write((write.line + "\n").toByteArray(StandardCharsets.UTF_8))
        process.outputStream.flush()
        write.result.complete(true)
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (_: IOException) {
      fail(BoundedProtocolInput.Failed(BoundedProtocolInput.Kind.Transport))
    }
  }

  private fun readFrames(input: InputStream) {
    val chunk = ByteArray(4096)
    try {
      while (!closed.get()) {
        val count = input.read(chunk)
        if (count < 0) {
          fail(BoundedProtocolInput.Exited)
          return
        }
        val failure = framer.accept(chunk, count) { line ->
          incoming.trySend(BoundedProtocolInput.Line(line)).isSuccess
        }
        if (failure != null) {
          fail(BoundedProtocolInput.Failed(failure))
          return
        }
      }
    } catch (_: IOException) {
      fail(BoundedProtocolInput.Failed(BoundedProtocolInput.Kind.Transport))
    }
  }

  private fun drainStderr(input: InputStream) {
    val chunk = ByteArray(1024)
    try {
      while (true) {
        val count = input.read(chunk)
        if (count < 0) return
        synchronized(stderrTail) {
          val text = String(chunk, 0, count, StandardCharsets.UTF_8)
          stderrTail.append(text)
          if (stderrTail.length > MAX_STDERR_CHARS) stderrTail.delete(0, stderrTail.length - MAX_STDERR_CHARS)
        }
      }
    } catch (_: IOException) {
      if (!closed.get()) ownership.diagnostics.record("usage.process_stderr_failure")
    }
  }

  private fun fail(outcome: BoundedProtocolInput) {
    if (terminal.complete(outcome)) {
      closed.set(true)
      incoming.cancel()
      pendingWrite.get()?.result?.complete(false)
      writes.clear()
      writer.interrupt()
      thread(isDaemon = true, name = "usage-process-cleanup") { cleanup() }
    }
  }

  private fun cleanup() {
    ChatProcesses.destroyOwnedTree(ownership)
  }

  private data class PendingWrite(val line: String, val result: CompletableDeferred<Boolean>)

  companion object { private const val MAX_STDERR_CHARS = 2000 }
}

private val approvalShutdownHookInstalled = AtomicBoolean(false)

/** Installs the one JVM shutdown hook that releases the open session's approvals on app exit; later calls do nothing. */
internal fun installApprovalShutdownHook(release: () -> Unit) {
  if (approvalShutdownHookInstalled.compareAndSet(false, true)) {
    Runtime.getRuntime().addShutdownHook(Thread(release, "skill-bill-approval-shutdown"))
  }
}

private object ChatProcesses {
  const val STDERR_TAIL = 2000
  const val STDERR_DRAIN_MS = 2000L
  const val STOP_GRACE_MS = 3000L
  const val FORCE_WAIT_MS = 1000L
  val live: MutableSet<Process> = ConcurrentHashMap.newKeySet()
  private val boundedOwnership = ConcurrentHashMap<Process, BoundedProcessOwnership>()
  private val hookInstalled = AtomicBoolean(false)

  fun installShutdownHook() {
    if (hookInstalled.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(Thread({
        boundedOwnership.values.toList().forEach(::destroyOwnedTree)
        live.filterNot(boundedOwnership::containsKey).forEach(::signalTree)
      }, "chat-shutdown"))
    }
  }

  fun registerBounded(ownership: BoundedProcessOwnership) {
    val process = ownership.process
    boundedOwnership[process] = ownership
    live += process
    synchronized(ownership.lock) {
      observeLocked(ownership)
      ownership.observer = BoundedProcessObservationScheduling.scheduler.schedule(
        { observe(ownership) },
        OBSERVATION_INTERVAL_MS,
      )
    }
    installShutdownHook()
  }

  private fun observe(ownership: BoundedProcessOwnership) {
    synchronized(ownership.lock) {
      if (!ownership.cleanupStarted) observeLocked(ownership)
    }
  }

  private fun observeLocked(ownership: BoundedProcessOwnership) {
    val roots = buildList {
      if (isAlive(ownership.root)) add(ownership.root)
      ownership.descendants.filterTo(this, ::isAlive)
    }
    roots.forEach { root ->
      try {
        root.descendants().use { descendants -> descendants.forEach { ownership.descendants.add(it) } }
      } catch (_: Exception) {
        if (ownership.observationFailureRecorded.compareAndSet(false, true)) {
          ownership.diagnostics.record("usage.process_observation_failure")
        }
      }
    }
  }

  fun destroyOwnedTree(ownership: BoundedProcessOwnership) {
    val current = Thread.currentThread()
    val owner = synchronized(ownership.lock) {
      if (ownership.cleanupStarted) {
        if (ownership.cleanupThread === current) return
        false
      } else {
        ownership.cleanupStarted = true
        ownership.cleanupThread = current
        ownership.observer?.cancel()
        observeLocked(ownership)
        true
      }
    }
    if (!owner) {
      ownership.cleanupFinished.join()
      return
    }
    cleanupOwned(ownership)
  }

  private fun cleanupOwned(ownership: BoundedProcessOwnership) {
    val process = ownership.process
    val root = ownership.root
    val known = synchronized(ownership.lock) { (ownership.descendants + root).toList() }
    var failed = false
    known.forEach { if (isAlive(it) && runCatching { it.destroy() }.isFailure) failed = true }
    if (failed) ownership.recordCleanupFailure()

    val gracefulDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MS)
    waitForHandles(known, gracefulDeadline)
    val survivors = known.filter(::isAlive)
    if (survivors.isNotEmpty()) {
      ownership.diagnostics.record("chat.process_force_killed")
      survivors.forEach { if (runCatching { it.destroyForcibly() }.isFailure) ownership.recordCleanupFailure() }
    }
    waitForHandles(known, Long.MAX_VALUE)
    runCatching { process.outputStream.close() }.onFailure { ownership.recordCleanupFailure() }
    runCatching { process.inputStream.close() }.onFailure { ownership.recordCleanupFailure() }
    runCatching { process.errorStream.close() }.onFailure { ownership.recordCleanupFailure() }
    live -= process
    boundedOwnership.remove(process, ownership)
    ownership.releaseStartup()
    ownership.cleanupFinished.complete(Unit)
  }

  private fun waitForHandles(handles: List<ProcessHandle>, deadlineNanos: Long) {
    var interrupted = false
    handles.filter(::isAlive).forEach { handle ->
      while (isAlive(handle)) {
        try {
          if (deadlineNanos == Long.MAX_VALUE) {
            handle.onExit().get()
          } else {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0L) break
            handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
          }
        } catch (_: InterruptedException) {
          interrupted = true
        } catch (_: java.util.concurrent.TimeoutException) {
          break
        } catch (_: Exception) {
          if (isAlive(handle)) {
            if (deadlineNanos != Long.MAX_VALUE) break
            ownershipWait()
          }
        }
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
  }

  private fun ownershipWait() {
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100))
  }

  private fun isAlive(handle: ProcessHandle): Boolean = runCatching { handle.isAlive }.getOrDefault(true)

  fun destroyTree(process: Process, diagnostics: Diagnostics) {
    if (!process.isAlive || !live.remove(process)) return
    val handle = process.toHandle()
    val descendants = handle.descendants().toList()
    descendants.forEach { it.destroy() }
    process.destroy()
    process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)
    val survivors = (descendants + handle).filter { it.isAlive }
    if (survivors.isEmpty()) return
    survivors.forEach { it.destroyForcibly() }
    diagnostics.record("chat.process_force_killed")
    process.waitFor(FORCE_WAIT_MS, TimeUnit.MILLISECONDS)
  }

  private fun signalTree(process: Process) {
    process.toHandle().descendants().forEach { it.destroy() }
    process.destroy()
  }

  private const val OBSERVATION_INTERVAL_MS = 100L
}

private class BoundedProcessOwnership(
  val process: Process,
  val diagnostics: Diagnostics,
  private val release: () -> Unit,
) {
  val root = process.toHandle()
  val lock = Any()
  val descendants = LinkedHashSet<ProcessHandle>()
  val cleanupFinished = CompletableFuture<Unit>()
  val observationFailureRecorded = AtomicBoolean(false)
  private val cleanupFailureRecorded = AtomicBoolean(false)
  var observer: BoundedProcessObservationHandle? = null
  var cleanupStarted = false
  var cleanupThread: Thread? = null

  fun recordCleanupFailure() {
    if (cleanupFailureRecorded.compareAndSet(false, true)) diagnostics.record("usage.process_cleanup_failure")
  }

  fun releaseStartup() = release()
}

internal fun interface BoundedProcessObservationHandle {
  fun cancel()
}

internal fun interface BoundedProcessObservationScheduler {
  fun schedule(task: () -> Unit, intervalMillis: Long): BoundedProcessObservationHandle
}

internal object BoundedProcessObservationScheduling {
  @Volatile
  var scheduler: BoundedProcessObservationScheduler = ExecutorBoundedProcessObservationScheduler
}

private object ExecutorBoundedProcessObservationScheduler : BoundedProcessObservationScheduler {
  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "usage-process-observer").apply { isDaemon = true }
  }

  override fun schedule(task: () -> Unit, intervalMillis: Long): BoundedProcessObservationHandle {
    val scheduled: ScheduledFuture<*> = executor.scheduleWithFixedDelay(
      task,
      intervalMillis,
      intervalMillis,
      TimeUnit.MILLISECONDS,
    )
    return BoundedProcessObservationHandle { scheduled.cancel(false) }
  }
}
