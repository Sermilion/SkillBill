package skillbill.infrastructure.host.jvm

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption

fun atomicWriteBytes(
  path: Path,
  bytes: ByteArray,
) {
  val parent = path.parent
  if (parent != null) {
    Files.createDirectories(parent)
  }
  val tempDir = parent ?: Path.of(".")
  val temp = Files.createTempFile(tempDir, "${path.fileName}.", ".tmp")
  Closeable { Files.deleteIfExists(temp) }.use {
    Files.write(temp, bytes)
    FileChannel.open(temp, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    atomicMoveReplacing(temp, path)
  }
}

fun atomicWriteString(
  path: Path,
  text: String,
) {
  atomicWriteBytes(path, text.toByteArray())
}

fun replaceDirectory(
  source: Path,
  target: Path,
  onDegraded: (String) -> Unit,
) {
  try {
    Files.move(source, target, ATOMIC_MOVE)
  } catch (error: AtomicMoveNotSupportedException) {
    onDegraded("AtomicMoveNotSupportedException: ${error.message.orEmpty()}")
    deleteRecursively(target)
    Files.move(source, target)
  } catch (error: FileAlreadyExistsException) {
    onDegraded("FileAlreadyExistsException: ${error.message.orEmpty()}")
  } catch (error: FileSystemException) {
    onDegraded("${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}")
    deleteRecursively(target)
    Files.move(source, target)
  }
}

fun deleteRecursively(root: Path) {
  if (!Files.exists(root)) return
  Files.walk(root).use { stream ->
    stream.toList().sortedDescending().forEach { Files.deleteIfExists(it) }
  }
}

fun rollbackRestoreBytes(
  path: Path,
  bytes: ByteArray,
) {
  atomicWriteBytes(path, bytes)
}

fun rollbackDeleteIfExists(path: Path) {
  Files.deleteIfExists(path)
}

fun rollbackDeleteEmptyDirectory(path: Path) {
  if (Files.isDirectory(path) && Files.list(path).use { stream -> !stream.findAny().isPresent }) {
    Files.deleteIfExists(path)
  }
}

fun rollbackDeleteRegularFileOrSymlink(path: Path) {
  if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
    Files.deleteIfExists(path)
  }
}

fun rollbackDeletePathEntry(target: Path) {
  if (Files.isSymbolicLink(target)) {
    Files.deleteIfExists(target)
    return
  }
  if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
    deleteRecursively(target)
    return
  }
  Files.deleteIfExists(target)
}

fun requirePathContainedIn(
  candidate: Path,
  root: Path,
  lazyMessage: () -> Any,
) {
  require(pathContainedIn(candidate, root), lazyMessage)
}

fun pathContainedIn(
  candidate: Path,
  root: Path,
): Boolean {
  val normalizedCandidate = candidate.toAbsolutePath().normalize()
  val normalizedRoot = root.toAbsolutePath().normalize()
  if (Files.isSymbolicLink(normalizedCandidate)) return false
  val realRoot = resolveExistingAncestor(normalizedRoot)
  val realCandidate = resolveExistingAncestor(normalizedCandidate)
  return realCandidate.startsWith(realRoot)
}

private fun resolveExistingAncestor(path: Path): Path =
  try {
    path.toRealPath()
  } catch (error: NoSuchFileException) {
    val parent = path.parent ?: throw error
    resolveExistingAncestor(parent).resolve(path.fileName)
  }
