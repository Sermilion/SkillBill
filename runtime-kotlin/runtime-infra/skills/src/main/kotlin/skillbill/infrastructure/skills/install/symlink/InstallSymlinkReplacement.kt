package skillbill.infrastructure.skills.install.symlink

import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.infrastructure.host.jvm.rollbackDeleteIfExists
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

internal fun createReplacementSymlinkWithGuidance(
  linkPath: Path,
  linkTarget: Path,
) {
  createManagedSymlinkWithGuidance(linkPath, linkTarget, replaceExisting = true)
}

internal fun createNewSymlinkWithGuidance(
  linkPath: Path,
  linkTarget: Path,
) {
  createManagedSymlinkWithGuidance(linkPath, linkTarget, replaceExisting = false)
}

internal fun resolveSymlinkTarget(linkPath: Path): Path? =
  runCatching {
    val rawTarget = Files.readSymbolicLink(linkPath)
    val resolvedTarget = if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget)
    resolvedTarget.toAbsolutePath().normalize()
  }.getOrNull()

private fun createManagedSymlinkWithGuidance(
  linkPath: Path,
  linkTarget: Path,
  replaceExisting: Boolean,
) {
  val tempLink = linkPath.parent.resolve(".${linkPath.fileName}.tmp-${UUID.randomUUID()}").normalize()
  val oldTarget = if (replaceExisting) readSymlinkTargetOrNull(linkPath) else null
  try {
    createSymbolicLinkWithGuidance(tempLink, linkTarget)
    if (replaceExisting) {
      Files.deleteIfExists(linkPath)
    }
    moveManagedLink(tempLink, linkPath)
  } catch (error: IOException) {
    restoreOriginalLinkIfNeeded(replaceExisting, oldTarget, linkPath)
    throw error
  } catch (error: UnsupportedOperationException) {
    restoreOriginalLinkIfNeeded(replaceExisting, oldTarget, linkPath)
    throw error
  } finally {
    runCatching { rollbackDeleteIfExists(tempLink) }
  }
}

private fun restoreOriginalLinkIfNeeded(
  replaceExisting: Boolean,
  oldTarget: Path?,
  linkPath: Path,
) {
  if (replaceExisting && oldTarget != null && !Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
    runCatching { createSymbolicLinkWithGuidance(linkPath, oldTarget) }
  }
}

private fun createSymbolicLinkWithGuidance(
  linkPath: Path,
  linkTarget: Path,
) {
  try {
    Files.createSymbolicLink(linkPath, linkTarget)
  } catch (error: UnsupportedOperationException) {
    throw symbolicLinkFailure(linkPath, error)
  } catch (error: FileSystemException) {
    throw symbolicLinkFailure(linkPath, error)
  }
}

private fun moveManagedLink(
  tempLink: Path,
  linkPath: Path,
) {
  if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
    throw FileAlreadyExistsException(linkPath.toString())
  }
  atomicMoveReplacing(tempLink, linkPath)
}

private fun readSymlinkTargetOrNull(linkPath: Path): Path? =
  runCatching {
    val rawTarget = Files.readSymbolicLink(linkPath)
    if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget).toAbsolutePath().normalize()
  }.getOrNull()
