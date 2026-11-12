package skillbill.infrastructure.workflow.goalworktree

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

internal fun <T> withWorktreeRecoveryLock(
  path: Path,
  unavailable: () -> T,
  operation: () -> T,
): T {
  Files.createDirectories(path.parent)
  val lockPath = path.resolveSibling("${path.fileName}.recovery-lock")
  return FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
    val lock =
      try {
        channel.tryLock()
      } catch (_: OverlappingFileLockException) {
        null
      }
    if (lock == null) unavailable() else lock.use { operation() }
  }
}
