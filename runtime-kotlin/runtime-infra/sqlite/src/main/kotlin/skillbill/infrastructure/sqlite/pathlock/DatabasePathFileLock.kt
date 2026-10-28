package skillbill.infrastructure.sqlite.pathlock

import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.cancellation.CancellationException

internal object DatabasePathFileLock {
  fun acquire(
    databasePath: Path,
    diagnostics: RuntimeDiagnostics,
  ): DatabasePathProtectionResult {
    var canonicalPath = databasePath
    val held = PendingLock()
    return try {
      canonicalPath = canonicalPath(databasePath)
      val processLock = localLocks.computeIfAbsent(canonicalPath) { ReentrantLock() }
      held.localLock = processLock
      if (!processLock.tryLock()) return unavailable(diagnostics, canonicalPath, "contended")
      val lockPath = lockPath(canonicalPath)
      val openedChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      held.channel = openedChannel
      val acquiredFileLock =
        try {
          openedChannel.tryLock()
        } catch (_: OverlappingFileLockException) {
          null
        }
      held.fileLock = acquiredFileLock
      if (acquiredFileLock == null) {
        openedChannel.close()
        processLock.unlock()
        return unavailable(diagnostics, canonicalPath, "contended")
      }
      DatabasePathProtectionResult.Acquired(
        HeldDatabasePathLock(openedChannel, acquiredFileLock, processLock),
      )
    } catch (failure: CancellationException) {
      release(held.fileLock, held.channel, held.localLock, diagnostics)
      throw failure
    } catch (failure: IOException) {
      unavailableAfterRelease(failure, held, diagnostics, canonicalPath)
    } catch (failure: SecurityException) {
      unavailableAfterRelease(failure, held, diagnostics, canonicalPath)
    } catch (failure: UnsupportedOperationException) {
      unavailableAfterRelease(failure, held, diagnostics, canonicalPath)
    } catch (failure: IllegalArgumentException) {
      unavailableAfterRelease(failure, held, diagnostics, canonicalPath)
    }
  }

  private fun unavailableAfterRelease(
    failure: Exception,
    held: PendingLock,
    diagnostics: RuntimeDiagnostics,
    path: Path,
  ): DatabasePathProtectionResult.Unavailable {
    release(held.fileLock, held.channel, held.localLock, diagnostics)
    if (Thread.currentThread().isInterrupted) throw failure
    val reason = failure.message?.takeIf(String::isNotBlank) ?: failure::class.simpleName.orEmpty()
    return unavailable(diagnostics, path, "unavailable:$reason", failure)
  }

  private class PendingLock {
    var localLock: ReentrantLock? = null
    var channel: FileChannel? = null
    var fileLock: FileLock? = null
  }

  private fun release(
    fileLock: FileLock?,
    channel: FileChannel?,
    localLock: ReentrantLock?,
    diagnostics: RuntimeDiagnostics,
  ) {
    var cleanupFailure: Exception? = null
    try {
      fileLock?.release()
    } catch (failure: IOException) {
      cleanupFailure = failure
    } catch (failure: SecurityException) {
      cleanupFailure = failure
    }
    try {
      channel?.close()
    } catch (failure: IOException) {
      if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
    } catch (failure: SecurityException) {
      if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
    }
    localLock?.let { if (it.isHeldByCurrentThread) it.unlock() }
    cleanupFailure?.let { failure ->
      diagnostics.warning("seam=database_path_protection value_expected=released value_used=cleanup_failed", failure)
    }
  }

  private fun canonicalPath(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    val parent = absolute.parent?.toRealPath() ?: throw IOException("Database path has no parent directory.")
    val selected = parent.resolve(absolute.fileName)
    return try {
      selected.toRealPath()
    } catch (_: NoSuchFileException) {
      selected
    }
  }

  private fun lockPath(canonicalPath: Path): Path {
    val digest =
      MessageDigest.getInstance("SHA-256")
        .digest(canonicalPath.toString().toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
    return canonicalPath.resolveSibling(".${canonicalPath.fileName}.$digest.lock")
  }

  private fun unavailable(
    diagnostics: RuntimeDiagnostics,
    path: Path,
    cause: String,
    failure: Throwable? = null,
  ): DatabasePathProtectionResult.Unavailable {
    diagnostics.warning(
      "seam=database_path_protection value_expected=acquired value_used=unavailable " +
        "cause=$cause db_path=$path",
      failure,
    )
    return DatabasePathProtectionResult.Unavailable("Could not protect database path '$path' ($cause).")
  }

  private class HeldDatabasePathLock(
    private val channel: FileChannel,
    private val fileLock: FileLock,
    private val localLock: ReentrantLock,
  ) : AutoCloseable {
    override fun close() {
      try {
        fileLock.release()
      } finally {
        try {
          channel.close()
        } finally {
          localLock.unlock()
        }
      }
    }
  }

  private val localLocks = ConcurrentHashMap<Path, ReentrantLock>()
}
