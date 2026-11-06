package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
  private val hookInstalled = AtomicBoolean(false)

  fun installShutdownHook() {
    if (hookInstalled.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(Thread({ live.forEach(::signalTree) }, "chat-shutdown"))
    }
  }

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
}
