package skillbill.infrastructure.skills.install.apply

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
  tryCreateReplacementSymlinkWithGuidance(linkPath, linkTarget)?.let { throw it.error }
}

internal fun createNewSymlinkWithGuidance(
  linkPath: Path,
  linkTarget: Path,
) {
  tryCreateNewSymlinkWithGuidance(linkPath, linkTarget)?.let { throw it.error }
}

internal fun tryCreateReplacementSymlinkWithGuidance(
  linkPath: Path,
  linkTarget: Path,
): InstallSymlinkFailure? = createManagedSymlinkWithGuidance(linkPath, linkTarget, replaceExisting = true)

internal fun tryCreateNewSymlinkWithGuidance(
  linkPath: Path,
  linkTarget: Path,
): InstallSymlinkFailure? = createManagedSymlinkWithGuidance(linkPath, linkTarget, replaceExisting = false)

private fun createManagedSymlinkWithGuidance(
  linkPath: Path,
  linkTarget: Path,
  replaceExisting: Boolean,
): InstallSymlinkFailure? {
  val tempLink = linkPath.parent.resolve(".${linkPath.fileName}.tmp-${UUID.randomUUID()}").normalize()
  val oldTarget = if (replaceExisting) readSymlinkTargetOrNull(linkPath) else null
  try {
    createSymbolicLinkWithGuidance(tempLink, linkTarget)?.let { failure ->
      restoreOriginalLinkIfNeeded(replaceExisting, oldTarget, linkPath)
      return failure
    }
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
  return null
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
): InstallSymlinkFailure? {
  try {
    Files.createSymbolicLink(linkPath, linkTarget)
    return null
  } catch (error: UnsupportedOperationException) {
    return InstallSymlinkFailure(linkPath, symbolicLinkFailure(linkPath, error))
  } catch (error: FileSystemException) {
    return InstallSymlinkFailure(linkPath, symbolicLinkFailure(linkPath, error))
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
