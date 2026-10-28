package dev.skillbill.desktop.core.data.signal

import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW

internal interface SignalWatch : AutoCloseable {
  /** Suspends until the signal file may have changed. An `OVERFLOW` counts, because events for the file may have been lost. */
  suspend fun awaitChange()
}

/** Watches the signal file's directory and wakes for events naming the file; an atomic replace arrives as `ENTRY_CREATE`. */
internal class NativeSignalWatch(signalPath: Path) : SignalWatch {
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

  override suspend fun awaitChange() {
    while (true) {
      val key = runInterruptible { watch.take() }
      val events = key.pollEvents()
      if (!key.reset()) throw IOException("Commit signal observation ended.")
      if (events.any { it.kind() == OVERFLOW }) return
      if (events.any { it.kind() != ENTRY_DELETE && (it.context() as? Path)?.fileName == signalName }) return
    }
  }

  override fun close() = watch.close()
}
