package skillbill.infrastructure.skills.install.apply

import skillbill.infrastructure.skills.install.symlink.removeOwnedSymlink
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors

internal object InstallCleanupOperations {
  fun isOwnedSymlink(
    path: Path,
    installedSkillsRoot: Path,
  ): Boolean = symlinkPointsInto(path, installedSkillsRoot.toAbsolutePath().normalize()) == true

  fun cleanupAgentTarget(
    targetDir: Path,
    skillNames: List<String>,
    managedInstallMarker: String,
    installedSkillsRoot: Path? = null,
    preservedTargetNames: Set<String> = emptySet(),
  ): Pair<List<Path>, List<Path>> {
    if (!Files.isDirectory(targetDir)) return emptyList<Path>() to emptyList()
    val removed = mutableListOf<Path>()
    val skipped = mutableListOf<Path>()
    Files.list(targetDir).use { stream ->
      stream
        .filter { path -> path.fileName.toString() !in preservedTargetNames }
        .filter { path ->
          Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(path.resolve(managedInstallMarker), LinkOption.NOFOLLOW_LINKS)
        }
        .forEach { path -> removeCleanupTarget(path, managedInstallMarker, removed, skipped, installedSkillsRoot) }
    }

    if (installedSkillsRoot != null) {
      val root = installedSkillsRoot.toAbsolutePath().normalize()
      val owned =
        Files.list(targetDir).use { stream ->
          stream
            .filter { path -> path.fileName.toString() !in preservedTargetNames }
            .filter { path -> symlinkPointsInto(path, root) == true }
            .collect(Collectors.toList())
        }
      owned.forEach { path -> removeCleanupTarget(path, managedInstallMarker, removed, skipped, installedSkillsRoot) }
    }
    skillNames.distinct().forEach { name ->
      val target = targetDir.resolve(name)
      if (target !in removed) {
        removeCleanupTarget(target, managedInstallMarker, removed, skipped, installedSkillsRoot)
      }
    }
    return removed to skipped
  }

  private fun symlinkPointsInto(
    path: Path,
    root: Path,
  ): Boolean? {
    if (!Files.isSymbolicLink(path)) return false
    return try {
      val target = Files.readSymbolicLink(path)
      val resolved = (if (target.isAbsolute) target else path.parent.resolve(target)).toAbsolutePath().normalize()
      resolved.startsWith(root)
    } catch (_: IOException) {
      null
    }
  }

  private fun removeCleanupTarget(
    target: Path,
    managedInstallMarker: String,
    removed: MutableList<Path>,
    skipped: MutableList<Path>,
    installedSkillsRoot: Path?,
  ) {
    if (Files.isSymbolicLink(target)) {
      if (installedSkillsRoot != null && removeOwnedSymlink(target, installedSkillsRoot) != null) {
        removed.add(target)
      } else {
        skipped.add(target)
      }
    } else if (
      Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) &&
      Files.isRegularFile(target.resolve(managedInstallMarker), LinkOption.NOFOLLOW_LINKS)
    ) {
      deleteTree(target)
      removed.add(target)
    } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      skipped.add(target)
    }
  }

  private fun deleteTree(target: Path) {
    Files.walkFileTree(
      target,
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(
          file: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          Files.delete(file)
          return CONTINUE
        }

        override fun postVisitDirectory(
          dir: Path,
          exc: IOException?,
        ): FileVisitResult {
          if (exc != null) throw exc
          Files.delete(dir)
          return CONTINUE
        }
      },
    )
  }
}
