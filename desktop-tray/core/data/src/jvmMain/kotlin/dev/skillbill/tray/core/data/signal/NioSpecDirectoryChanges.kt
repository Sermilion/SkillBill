package dev.skillbill.tray.core.data.signal

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.data.port.SpecDirectoryChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes

private const val ARCHIVE_DIRECTORY = "done"

/**
 * Watches a project's spec directory with the platform watch service: the root, its folders and every `done` folder, but
 * not the archived bundles inside `done`, which can number in the hundreds. Directories created later are registered as
 * the watch service reports them. There is no polling.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
open class NioSpecDirectoryChanges(private val diagnostics: Diagnostics) : SpecDirectoryChanges {
  /** Overridable so a test can observe that cancellation closes the service it was given. */
  internal open fun newWatchService(root: Path): WatchService = root.fileSystem.newWatchService()

  override fun changes(specsRoot: String): Flow<Unit> = callbackFlow {
    val root = existingDirectory(specsRoot)
    if (root == null) {
      close()
      return@callbackFlow
    }
    val watch = newWatchService(root)
    try {
      register(watch, root, root)
    } catch (failure: Throwable) {
      closeWatch(watch)
      throw failure
    }
    val worker = launch {
      try {
        while (isActive) {
          val key = runInterruptible { watch.take() }
          val directory = key.watchable() as? Path
          for (event in key.pollEvents()) {
            if (event.kind() == ENTRY_CREATE && directory != null) {
              (event.context() as? Path)?.let { registerCreated(watch, root, directory.resolve(it)) }
            }
          }
          trySend(Unit)
          key.reset()
        }
      } catch (_: ClosedWatchServiceException) {
      }
    }
    awaitClose {
      worker.cancel()
      closeWatch(watch)
    }
  }.flowOn(Dispatchers.IO).buffer(Channel.CONFLATED)

  private fun existingDirectory(specsRoot: String): Path? {
    if (specsRoot.isBlank()) return null
    return try {
      Path.of(specsRoot).takeIf(Files::isDirectory)
    } catch (_: InvalidPathException) {
      null
    }
  }

  private fun registerCreated(watch: WatchService, root: Path, created: Path) {
    try {
      if (Files.isDirectory(created, LinkOption.NOFOLLOW_LINKS)) register(watch, root, created)
    } catch (_: IOException) {
      diagnostics.record("spec_watch_register_failed")
    }
  }

  private fun register(watch: WatchService, root: Path, start: Path) {
    Files.walkFileTree(
      start,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
          val hidden = dir.fileName?.toString()?.startsWith(".") == true
          val archivedBundle = dir.parent?.fileName?.toString() == ARCHIVE_DIRECTORY
          if (dir != root && (hidden || archivedBundle)) return FileVisitResult.SKIP_SUBTREE
          return try {
            dir.register(watch, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            FileVisitResult.CONTINUE
          } catch (_: IOException) {
            // A folder removed mid-walk must not stop the watcher for the folders that remain.
            diagnostics.record("spec_watch_register_failed")
            FileVisitResult.SKIP_SUBTREE
          }
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
      },
    )
  }

  private fun closeWatch(watch: WatchService) {
    try {
      watch.close()
    } catch (_: IOException) {
      diagnostics.record("spec_watch_close_failed")
    }
  }
}
