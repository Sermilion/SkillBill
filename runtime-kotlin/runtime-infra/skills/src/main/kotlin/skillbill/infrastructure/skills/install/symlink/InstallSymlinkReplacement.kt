package skillbill.infrastructure.skills.install.symlink

import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.ListedSkillNames
import skillbill.install.model.SupportedAgent
import skillbill.ports.repository.toFileLocation
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

internal fun obsoleteDispatcherLinkPaths(
  targetDir: Path,
  isClaudeSkillsTarget: Boolean,
): List<Path> =
  buildList {
    add(targetDir.resolve(ListedSkillNames.DISPATCHER))
    if (isClaudeSkillsTarget && targetDir.fileName?.toString() == "skills") {
      add(targetDir.resolveSibling("commands").resolve(ListedSkillNames.DISPATCHER))
    }
  }

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
  if (!Files.isSymbolicLink(linkPath)) {
    null
  } else {
    try {
      val rawTarget = Files.readSymbolicLink(linkPath)
      val resolvedTarget = if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget)
      resolvedTarget.toAbsolutePath().normalize()
    } catch (_: IOException) {
      null
    }
  }

internal fun removeOwnedSymlink(
  linkPath: Path,
  installedSkillsRoot: Path,
): Path? {
  if (!Files.isSymbolicLink(linkPath)) return null
  val rawTarget = Files.readSymbolicLink(linkPath)
  val resolvedTarget =
    (if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget))
      .toAbsolutePath()
      .normalize()
  if (!resolvedTarget.startsWith(installedSkillsRoot.toAbsolutePath().normalize())) return null
  Files.delete(linkPath)
  return rawTarget
}

internal data class RetiredDispatcherLinks(
  val rawTargets: Map<Path, Path>,
  val issues: List<InstallApplyIssue>,
)

internal fun retireObsoleteDispatcherLinks(
  targetDir: Path,
  isClaudeSkillsTarget: Boolean,
  installedSkillsRoot: Path,
  agent: SupportedAgent?,
): RetiredDispatcherLinks {
  val rawTargets = mutableMapOf<Path, Path>()
  val issues = mutableListOf<InstallApplyIssue>()
  obsoleteDispatcherLinkPaths(targetDir, isClaudeSkillsTarget).forEach { path ->
    catchingNonCancellation { removeOwnedSymlink(path, installedSkillsRoot) }
      .onSuccess { rawTarget ->
        if (rawTarget != null) {
          rawTargets[path] = rawTarget
        } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
          issues +=
            InstallApplyIssue(
              kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
              message = "Existing legacy dispatcher target at $path was preserved.",
              skillName = ListedSkillNames.DISPATCHER,
              agent = agent,
              path = path.toFileLocation(),
            )
        }
      }
      .onFailure { error ->
        issues +=
          InstallApplyIssue(
            kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
            message = error.message.orEmpty(),
            skillName = ListedSkillNames.DISPATCHER,
            agent = agent,
            path = path.toFileLocation(),
            causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
          )
      }
  }
  return RetiredDispatcherLinks(rawTargets, issues)
}

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
    restoreOriginalLinkIfNeeded(replaceExisting, oldTarget, linkPath, error)
    throw error
  } catch (error: UnsupportedOperationException) {
    restoreOriginalLinkIfNeeded(replaceExisting, oldTarget, linkPath, error)
    throw error
  } finally {
    tempLink.toFile().delete()
  }
}

private fun restoreOriginalLinkIfNeeded(
  replaceExisting: Boolean,
  oldTarget: Path?,
  linkPath: Path,
  failure: Throwable,
) {
  if (replaceExisting && oldTarget != null && !Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
    catchingNonCancellation { createSymbolicLinkWithGuidance(linkPath, oldTarget) }.onFailure(failure::addSuppressed)
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
  if (!Files.isSymbolicLink(linkPath)) {
    null
  } else {
    try {
      val rawTarget = Files.readSymbolicLink(linkPath)
      if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget).toAbsolutePath().normalize()
    } catch (_: IOException) {
      null
    }
  }
