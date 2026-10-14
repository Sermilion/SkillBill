package dev.skillbill.tray.infrastructure.storage

import dev.skillbill.tray.infrastructure.RuntimeDiagnostics
import skillbill.contracts.persistence.DesktopCommitSignal
import skillbill.contracts.persistence.DesktopReadSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW

internal data class StoreChange(
  val initial: Boolean = false,
  val resynchronize: Boolean = false,
  val revisions: Map<String, Long> = emptyMap(),
)

private data class SignalSnapshot(val generation: String, val revisions: Map<String, Long>)

internal enum class StoreWatchHint { COMMIT, OVERFLOW }

internal interface StoreWatch : AutoCloseable {
  suspend fun awaitHint(): StoreWatchHint
}

internal class NativeCommitSignalWatch(private val signalPath: Path) : StoreWatch {
  private val watch = signalPath.fileSystem.newWatchService()
  private val signalName = signalPath.fileName

  init {
    try {
      requireNotNull(signalPath.parent).register(watch, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
    } catch (failure: Throwable) {
      try {
        watch.close()
      } catch (closing: Throwable) {
        failure.addSuppressed(closing)
      }
      throw failure
    }
  }

  override suspend fun awaitHint(): StoreWatchHint {
    while (true) {
      val key = runInterruptible { watch.take() }
      val events = key.pollEvents()
      if (!key.reset()) throw IOException("Commit signal observation ended.")
      if (events.any { it.kind() == OVERFLOW }) return StoreWatchHint.OVERFLOW
      if (events.any { event ->
          event.kind() in setOf(ENTRY_CREATE, ENTRY_MODIFY) &&
            (event.context() as? Path)?.fileName == signalName
        }) return StoreWatchHint.COMMIT
    }
  }

  override fun close() = watch.close()
}

internal class DesktopCommitSignalObserver(
  private val databasePath: Path,
  private val watchFactory: suspend (Path) -> StoreWatch = { NativeCommitSignalWatch(it) },
) {
  fun changes(ready: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred()): Flow<StoreChange> = callbackFlow {
    var watch: StoreWatch? = null
    var worker: Job? = null
    try {
      val signalPath = desktopCommitSignalPath(databasePath)
      Files.createDirectories(requireNotNull(signalPath.parent))
      val activeWatch = watchFactory(signalPath)
      watch = activeWatch
      ensureSignalFile(signalPath)
      var snapshot = readSignalSnapshot(signalPath) ?: run {
        RuntimeDiagnostics.record("commit_signal_payload_invalid")
        SignalSnapshot("", emptyMap())
      }
      if (!publishChange(StoreChange(initial = true, revisions = snapshot.revisions))) return@callbackFlow
      ready.complete(Unit)
      val activeWorker = launch(Dispatchers.IO) {
        try {
          while (isActive) {
            activeWatch.awaitHint()
            val next = readSignalSnapshot(signalPath)
            if (next == null) {
              RuntimeDiagnostics.record("commit_signal_payload_invalid")
              if (!publishChange(StoreChange(resynchronize = true, revisions = snapshot.revisions))) return@launch
              continue
            }
            if (next.generation != snapshot.generation) {
              val resynchronize = next.revisions == snapshot.revisions
              snapshot = next
              if (!publishChange(StoreChange(resynchronize = resynchronize, revisions = next.revisions))) return@launch
            }
          }
        } catch (failure: CancellationException) {
          throw failure
        } catch (failure: IOException) {
          close(failure)
        }
      }
      worker = activeWorker
      awaitClose { activeWorker.cancel() }
    } catch (failure: Throwable) {
      ready.completeExceptionally(failure)
      throw failure
    } finally {
      withContext(NonCancellable) {
        try {
          worker?.cancelAndJoin()
        } catch (_: Throwable) {
          RuntimeDiagnostics.record("commit_signal_worker_close_failed")
        }
        try {
          watch?.close()
        } catch (_: Throwable) {
          RuntimeDiagnostics.record("commit_signal_watch_close_failed")
        }
      }
    }
  }.buffer(Channel.CONFLATED)

  private fun ensureSignalFile(signalPath: Path) {
    try {
      Files.createFile(signalPath)
    } catch (_: FileAlreadyExistsException) {
    }
  }

  private fun readSignalSnapshot(signalPath: Path): SignalSnapshot? {
    if (!Files.exists(signalPath)) return SignalSnapshot("", emptyMap())
    val content = Files.readString(signalPath)
    if (content.isEmpty()) return SignalSnapshot("", emptyMap())
    if (content.length > MAX_SIGNAL_LENGTH) return null
    var generation: String? = null
    val parsed = buildMap<String, Long> {
      content.lineSequence().filter(String::isNotBlank).forEach { line ->
        val separator = line.indexOf(DesktopCommitSignal.REVISION_SEPARATOR)
        if (separator <= 0) return null
        val topic = line.substring(0, separator)
        val value = line.substring(separator + DesktopCommitSignal.REVISION_SEPARATOR.length)
        if (topic == DesktopCommitSignal.GENERATION_LABEL) {
          if (generation != null || value.isBlank()) return null
          generation = value
        } else if (topic in DesktopReadSchema.TOPICS || topic == DesktopReadSchema.ACTIVITY_TOPIC) {
          val revision = value.toLongOrNull()?.takeIf { it >= 0 } ?: return null
          if (put(topic, revision) != null) return null
        }
      }
    }
    val observedGeneration = generation ?: return null
    return parsed.takeIf { revisions -> DesktopReadSchema.TOPICS.all(revisions::containsKey) }
      ?.let { SignalSnapshot(observedGeneration, it) }
  }

  private fun kotlinx.coroutines.channels.ProducerScope<StoreChange>.publishChange(change: StoreChange): Boolean {
    val result = trySend(change)
    if (result.isSuccess) return true
    close(IOException("Commit signal delivery was interrupted.", result.exceptionOrNull()))
    return false
  }

  private companion object {
    const val MAX_SIGNAL_LENGTH = 512
  }
}

private fun desktopCommitSignalPath(databasePath: Path): Path =
  databasePath.toAbsolutePath().normalize().let { absolute ->
    absolute.resolveSibling("${absolute.fileName}${DesktopCommitSignal.FILE_SUFFIX}")
  }
