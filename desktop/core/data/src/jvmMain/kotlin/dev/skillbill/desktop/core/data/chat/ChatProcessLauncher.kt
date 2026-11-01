package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.di.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One agent process: [environment] replaces the inherited one, and [stdin] is written once and closed. */
data class ChatCommand(
  val argv: List<String>,
  val workingDirectory: String,
  val environment: Map<String, String>,
  val stdin: String,
)

/** What a chat process produces: stdout [Line]s, then [Exited] or, when it never started, [StartFailed]. */
sealed interface ChatProcessOutput {
  data class Line(val text: String) : ChatProcessOutput

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
}

/** The only chat file that names a process type. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProcessChatLauncher : ChatProcessLauncher {
  override fun run(command: ChatCommand): Flow<ChatProcessOutput> = runProcess(command) { process ->
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
class ProcessLiveChatLauncher : LiveChatProcessLauncher {
  override fun open(command: ChatCommand, input: ReceiveChannel<String>): Flow<ChatProcessOutput> = runProcess(command) { process ->
    try {
      process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
        for (line in input) {
          runInterruptible {
            writer.write(line)
            writer.newLine()
            writer.flush()
          }
        }
      }
    } catch (_: IOException) {
      // The process exited; its exit code and stderr end the stream.
    }
  }
}

// Starts [command], runs [feed] to supply stdin, and streams stdout until the process exits.
private fun runProcess(command: ChatCommand, feed: suspend (Process) -> Unit): Flow<ChatProcessOutput> =
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
        // A blocked readLine ignores cancellation; destroying the process ends the read.
        val killer = launch {
          try {
            awaitCancellation()
          } finally {
            ChatProcesses.destroyTree(process)
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
        try {
          process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) emit(ChatProcessOutput.Line(line))
          }
        } catch (_: IOException) {
          // A closed stdout ends the stream; the exit code and stderr tail still report the turn.
        }
        val code = process.waitFor()
        // A descendant can hold stderr open after claude exits; report the tail read so far.
        drain.join(ChatProcesses.STDERR_DRAIN_MS)
        emit(ChatProcessOutput.Exited(code, synchronized(stderr) { stderr.toString() }))
        feeding.cancel()
        killer.cancel()
      }
    } finally {
      ChatProcesses.destroyTree(process)
      ChatProcesses.live -= process
    }
  }.flowOn(Dispatchers.IO)

private object ChatProcesses {
  const val STDERR_TAIL = 2000
  const val STDERR_DRAIN_MS = 2000L
  val live: MutableSet<Process> = ConcurrentHashMap.newKeySet()
  private val hookInstalled = AtomicBoolean(false)

  fun installShutdownHook() {
    if (hookInstalled.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(Thread({ live.forEach(::destroyTree) }, "chat-shutdown"))
    }
  }

  fun destroyTree(process: Process) {
    if (!process.isAlive) return
    process.toHandle().descendants().forEach { it.destroy() }
    process.destroy()
  }
}
