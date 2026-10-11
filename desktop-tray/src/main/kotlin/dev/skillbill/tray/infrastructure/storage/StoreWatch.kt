package dev.skillbill.tray.infrastructure.storage

import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*

internal enum class StoreWatchHint { COMMIT, OVERFLOW }

internal interface StoreWatch : AutoCloseable {
  suspend fun awaitHint(): StoreWatchHint
}

internal class NativeStoreWatch(private val path: Path) : StoreWatch {
  private val watch = path.fileSystem.newWatchService()
  private val names = setOf(path.fileName.toString(), "${path.fileName}-wal", "${path.fileName}-shm", "${path.fileName}-journal")

  init {
    try {
      path.parent.register(watch, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
    } catch (failure: Throwable) {
      try { watch.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
      throw failure
    }
  }

  override suspend fun awaitHint(): StoreWatchHint {
    while (true) {
      val key = runInterruptible { watch.take() }
      val events = key.pollEvents()
      if (!key.reset()) throw IOException("Database directory observation ended.")
      if (events.any { it.kind() == OVERFLOW }) return StoreWatchHint.OVERFLOW
      if (events.any { (it.context() as? Path)?.fileName?.toString() in names }) return StoreWatchHint.COMMIT
    }
  }

  override fun close() = watch.close()
}
