package dev.skillbill.tray.infrastructure.storage

import androidx.sqlite.SQLiteConnection
import dev.skillbill.tray.infrastructure.RuntimeDiagnostics
import skillbill.contracts.persistence.DesktopReadSchema
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun storeFileIdentity(path: Path): String {
  val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
  return "${attributes.fileKey()}:${attributes.creationTime()}"
}

internal fun SQLiteConnection.dataVersion(): Long = prepare("PRAGMA data_version").use { statement ->
  check(statement.step())
  statement.getLong(0)
}

internal data class StoreChange(
  val replaced: Boolean = false,
  val resynchronize: Boolean = false,
  val topics: Set<String> = emptySet(),
  val revisions: Map<String, Long> = emptyMap(),
)

internal class ExternalCommitObserver(
  private val path: Path,
  private val recordReadWork: () -> Unit = {},
  private val watchFactory: suspend (Path) -> StoreWatch = { NativeStoreWatch(it) },
) {
  fun changes(ready: CompletableDeferred<Unit> = CompletableDeferred()): Flow<StoreChange> = callbackFlow {
    var watch: StoreWatch? = null
    var connection: SQLiteConnection? = null
    var worker: Job? = null
    try {
      val activeWatch = watchFactory(path)
      watch = activeWatch
      val identity = storeFileIdentity(path)
      val opened = ReadOnlyRoomDriver().open(path.toString())
      connection = opened
      recordReadWork()
      var revision = opened.dataVersion()
      recordReadWork()
      var topics = opened.topicRevisions()
      if (publishChange(StoreChange(resynchronize = true, revisions = topics))) ready.complete(Unit)
      worker = launch(Dispatchers.IO) {
        try {
          while (isActive) {
            withTimeoutOrNull(500) { activeWatch.awaitHint() }
            val replaced = try {
              !Files.exists(path) || storeFileIdentity(path) != identity
            } catch (_: java.nio.file.NoSuchFileException) {
              true
            }
            if (replaced) {
              publishChange(StoreChange(replaced = true))
              close()
              return@launch
            }
            recordReadWork()
            val next = opened.dataVersion()
            if (next != revision) {
              recordReadWork()
              val current = opened.topicRevisions()
              val changed = current.filter { (topic, value) -> topics[topic] != value }.keys
              topics = current
              revision = next
              if (changed.isNotEmpty()) publishChange(StoreChange(resynchronize = true, topics = changed, revisions = current))
            }
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: java.io.IOException) {
          close(error)
        } catch (error: androidx.sqlite.SQLiteException) {
          close(error)
        }
      }
      awaitClose { worker?.cancel() }
    } catch (error: Throwable) {
      ready.completeExceptionally(error)
      throw error
    } finally {
      withContext(NonCancellable) {
        try {
          worker?.cancelAndJoin()
        } catch (_: Throwable) {
          RuntimeDiagnostics.record("observer_worker_close_failed")
        }
        try {
          watch?.close()
        } catch (_: Throwable) {
          RuntimeDiagnostics.record("observer_watch_close_failed")
        }
        try {
          connection?.close()
        } catch (_: Throwable) {
          RuntimeDiagnostics.record("observer_connection_close_failed")
        }
      }
    }
  }.buffer(Channel.CONFLATED)

  private fun kotlinx.coroutines.channels.ProducerScope<StoreChange>.publishChange(change: StoreChange): Boolean {
    val result = trySend(change)
    if (result.isSuccess) return true
    val failure = java.io.IOException("Database change delivery was interrupted.", result.exceptionOrNull())
    close(failure)
    return false
  }
}

private fun SQLiteConnection.topicRevisions(): Map<String, Long> =
  prepare("SELECT topic, revision FROM ${DesktopReadSchema.REVISIONS_TABLE}").use { statement ->
    buildMap { while (statement.step()) put(statement.getText(0), statement.getLong(1)) }
  }
