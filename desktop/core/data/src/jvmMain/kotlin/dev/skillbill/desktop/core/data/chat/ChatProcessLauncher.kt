package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.di.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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

/** The only chat file that names a process type. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProcessChatLauncher : ChatProcessLauncher {
  override fun run(command: ChatCommand): Flow<ChatProcessOutput> = flow {
    val builder = ProcessBuilder(command.argv).directory(File(command.workingDirectory))
    builder.environment().clear()
    builder.environment().putAll(command.environment)
    val process = try {
      builder.start()
    } catch (failure: IOException) {
      emit(ChatProcessOutput.StartFailed(failure.message ?: failure.toString()))
      return@flow
    }
    live += process
    installShutdownHook()
    try {
      coroutineScope {
        // A blocked readLine ignores cancellation; destroying the process ends the read.
        val killer = launch {
          try {
            awaitCancellation()
          } finally {
            destroyTree(process)
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
                if (stderr.length > STDERR_TAIL) stderr.delete(0, stderr.length - STDERR_TAIL)
              }
            }
          }
        }
        try {
          process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(command.stdin) }
        } catch (_: IOException) {
          // The process exited before reading its input; its exit code and stderr report why.
        }
        try {
          process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) emit(ChatProcessOutput.Line(line))
          }
        } catch (_: IOException) {
          // A closed stdout ends the stream; the exit code and stderr tail still report the turn.
        }
        val code = process.waitFor()
        // A descendant can hold stderr open after claude exits; report the tail read so far.
        drain.join(STDERR_DRAIN_MS)
        emit(ChatProcessOutput.Exited(code, synchronized(stderr) { stderr.toString() }))
        killer.cancel()
      }
    } finally {
      destroyTree(process)
      live -= process
    }
  }.flowOn(Dispatchers.IO)

  private companion object {
    const val STDERR_TAIL = 2000
    const val STDERR_DRAIN_MS = 2000L
    val live: MutableSet<Process> = ConcurrentHashMap.newKeySet()
    val hookInstalled = AtomicBoolean(false)

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
}
