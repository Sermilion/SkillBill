package skillbill.infrastructure.host.system

import skillbill.error.core.SkillBillRuntimeException
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

object FileConfigDocumentLocks {
  fun canonicalPath(path: Path): Path = lockIdentity(path)

  fun lockedPath(path: Path): Path {
    val identity = lockIdentity(path)
    if (heldLocks.get()?.containsKey(identity) != true) {
      throw configDocumentIoException(identity, "lock", IOException("Config access requires its shared lock."))
    }
    return identity
  }

  fun lockFilePaths(paths: Collection<Path>): List<Path> =
    identities(paths).map { identity -> identity.resolveSibling("${identity.fileName}.lock") }

  fun <T> withLocks(
    paths: Collection<Path>,
    action: () -> T,
  ): T = withLocks(paths, interruptible = false, action)

  fun <T> withInterruptibleLocks(
    paths: Collection<Path>,
    action: () -> T,
  ): T = withLocks(paths, interruptible = true, action)

  private fun <T> withLocks(
    paths: Collection<Path>,
    interruptible: Boolean,
    action: () -> T,
  ): T {
    val lockIdentities = identities(paths)
    val held = heldLocks.get() ?: linkedMapOf<Path, HeldConfigLock>().also(heldLocks::set)
    val missing = lockIdentities.filterNot(held::containsKey)
    checkLockOrder(held.keys, missing)
    val acquired = mutableListOf<Path>()
    try {
      missing.forEach { identity ->
        held[identity] = acquireLock(identity, interruptible)
        acquired.add(identity)
      }
      requireStableIdentities(paths, lockIdentities)
    } catch (error: SkillBillRuntimeException) {
      releaseLocks(acquired)
      throw error
    } catch (error: InterruptedException) {
      releaseLocks(acquired)
      throw error
    } catch (error: IOException) {
      failAcquiring(acquired, lockIdentities, error)
    } catch (error: SecurityException) {
      failAcquiring(acquired, lockIdentities, error)
    } catch (error: UnsupportedOperationException) {
      failAcquiring(acquired, lockIdentities, error)
    } catch (error: OverlappingFileLockException) {
      failAcquiring(acquired, lockIdentities, error)
    }
    return try {
      action()
    } finally {
      releaseLocks(acquired)
    }
  }

  private fun checkLockOrder(
    held: Set<Path>,
    missing: List<Path>,
  ) {
    val lastHeld = held.maxByOrNull(Path::toString)
    if (lastHeld != null && missing.any { it.toString() < lastHeld.toString() }) {
      throw configDocumentIoException(lastHeld, "lock", IOException("Config locks must be acquired in sorted order."))
    }
  }

  private fun requireStableIdentities(
    paths: Collection<Path>,
    lockIdentities: List<Path>,
  ) {
    if (identities(paths) != lockIdentities) {
      throw configDocumentIoException(
        lockIdentities.firstOrNull() ?: Path.of("."),
        "lock",
        IOException("Config path identity changed while acquiring its lock."),
      )
    }
  }

  private fun failAcquiring(
    acquired: List<Path>,
    identities: List<Path>,
    error: Throwable,
  ): Nothing {
    releaseLocks(acquired)
    throw configDocumentIoException(identities.firstOrNull() ?: Path.of("."), "lock", error)
  }

  private fun acquireLock(
    identity: Path,
    interruptible: Boolean,
  ): HeldConfigLock {
    val mutex = pathLocks.computeIfAbsent(identity) { ReentrantLock() }
    try {
      if (interruptible) {
        mutex.lockInterruptibly()
      } else {
        mutex.lock()
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      throw InterruptedException("Config lock wait was interrupted.")
    }
    var channel: FileChannel? = null
    var acquired = false
    try {
      if (interruptible && Thread.interrupted()) {
        throw InterruptedException("Config lock wait was interrupted.")
      }
      Files.createDirectories(identity.parent)
      val lockPath = identity.resolveSibling("${identity.fileName}.lock")
      channel = openLockChannel(lockPath)
      val fileLock = exclusiveFileLock(channel, interruptible)
      acquired = true
      return HeldConfigLock(mutex, channel, fileLock)
    } finally {
      if (!acquired) {
        try {
          channel?.close()
        } finally {
          mutex.unlock()
        }
      }
    }
  }

  private fun openLockChannel(lockPath: Path): FileChannel {
    if (Files.isSymbolicLink(lockPath)) {
      throw IOException("Config lock path must not be a symbolic link.")
    }
    return FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS)
  }

  private fun exclusiveFileLock(
    channel: FileChannel,
    interruptible: Boolean,
  ): FileLock =
    try {
      channel.lock()
    } catch (error: ClosedByInterruptException) {
      interruptedFileLock(interruptible, error)
    } catch (error: InterruptedIOException) {
      interruptedFileLock(interruptible, error)
    }

  private fun interruptedFileLock(
    interruptible: Boolean,
    error: IOException,
  ): Nothing {
    Thread.currentThread().interrupt()
    if (interruptible) {
      throw InterruptedException("Config lock wait was interrupted.")
    }
    throw error
  }

  private fun lockIdentity(path: Path): Path {
    val absolute =
      try {
        path.toAbsolutePath().normalize()
      } catch (error: SecurityException) {
        failPath(path, error)
      } catch (error: UnsupportedOperationException) {
        failPath(path, error)
      }
    try {
      var existing = absolute
      val missingNames = mutableListOf<Path>()
      while (true) {
        val attributes =
          try {
            Files.readAttributes(existing, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
          } catch (_: NoSuchFileException) {
            null
          }
        if (attributes != null) {
          return missingNames.asReversed().fold(existing.toRealPath()) { parent, name -> parent.resolve(name) }
        }
        val name =
          existing.fileName
            ?: failPath(absolute, IOException("Config path has no existing ancestor."))
        missingNames.add(name)
        existing = existing.parent
          ?: failPath(absolute, IOException("Config path has no parent directory."))
      }
    } catch (error: SkillBillRuntimeException) {
      throw error
    } catch (error: IOException) {
      failPath(absolute, error)
    } catch (error: SecurityException) {
      failPath(absolute, error)
    } catch (error: UnsupportedOperationException) {
      failPath(absolute, error)
    }
  }

  private fun failPath(
    path: Path,
    error: Throwable,
  ): Nothing = throw configDocumentIoException(path, "lock", error)

  private fun releaseLocks(paths: List<Path>) {
    var failure: SkillBillRuntimeException? = null
    paths.asReversed().forEach { path ->
      val held = heldLocks.get() ?: return@forEach
      val lock = held.remove(path) ?: return@forEach
      try {
        try {
          lock.fileLock.release()
        } catch (error: IOException) {
          failure = failure.withSuppressed(configDocumentIoException(path, "unlock", error))
        } catch (error: SecurityException) {
          failure = failure.withSuppressed(configDocumentIoException(path, "unlock", error))
        }
        try {
          lock.channel.close()
        } catch (error: IOException) {
          failure = failure.withSuppressed(configDocumentIoException(path, "unlock", error))
        } catch (error: SecurityException) {
          failure = failure.withSuppressed(configDocumentIoException(path, "unlock", error))
        }
      } finally {
        lock.mutex.unlock()
        if (held.isEmpty()) heldLocks.remove()
      }
    }
    failure?.let { throw it }
  }

  private fun SkillBillRuntimeException?.withSuppressed(
    reported: SkillBillRuntimeException,
  ): SkillBillRuntimeException = this?.also { it.addSuppressed(reported) } ?: reported

  private fun identities(paths: Collection<Path>): List<Path> =
    paths.map(::lockIdentity).distinct().sortedBy(Path::toString)

  private val pathLocks = ConcurrentHashMap<Path, ReentrantLock>()
  private val heldLocks = ThreadLocal<MutableMap<Path, HeldConfigLock>>()
}

private data class HeldConfigLock(val mutex: ReentrantLock, val channel: FileChannel, val fileLock: FileLock)
