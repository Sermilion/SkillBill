package skillbill.infrastructure.skills.install
import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.host.resolveTelemetryConfigMigrationLockPaths
import skillbill.infrastructure.host.system.FileConfigDocumentLocks
import skillbill.ports.system.UninstallPathsPort
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator

@Inject
class FileSystemUninstallFileSystemGateway : UninstallPathsPort {
  override fun listImmediateDirectoryNames(root: Path): List<String> {
    if (!Files.isDirectory(root)) {
      return emptyList()
    }
    return Files.newDirectoryStream(root).use { entries ->
      entries.map { entry -> entry.fileName.toString() }
    }
  }

  override fun exists(path: Path): Boolean = Files.exists(path)

  override fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(path)

  override fun readSymbolicLink(path: Path): Path = Files.readSymbolicLink(path)

  override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

  override fun removeTree(path: Path): List<Path> {
    val removed = mutableListOf<Path>()
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach { entry ->
        if (Files.deleteIfExists(entry)) {
          removed.add(entry)
        }
      }
    }
    return removed
  }

  override fun <T> withConfigTransaction(
    userHome: Path,
    environment: Map<String, String>,
    action: () -> T,
  ): T = FileConfigDocumentLocks.withLocks(resolveTelemetryConfigMigrationLockPaths(environment, userHome), action)

  override fun configLockFilePaths(
    userHome: Path,
    environment: Map<String, String>,
  ): List<Path> = FileConfigDocumentLocks.lockFilePaths(resolveTelemetryConfigMigrationLockPaths(environment, userHome))

  override fun removeTreeExcept(
    path: Path,
    retainedPaths: Collection<Path>,
  ): List<Path> {
    val root = path.toAbsolutePath().normalize()
    val retained = retainedPaths.map { it.toAbsolutePath().normalize() }.toSet()
    val removed = mutableListOf<Path>()
    Files.walkFileTree(
      root,
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(
          file: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          val identity =
            if (Files.isSymbolicLink(
                file,
              )
            ) {
              file.toAbsolutePath().normalize()
            } else {
              FileConfigDocumentLocks.canonicalPath(file)
            }
          if (identity !in retained) delete(file)
          return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(
          dir: Path,
          error: IOException?,
        ): FileVisitResult {
          if (error != null) throw error
          val identity = FileConfigDocumentLocks.canonicalPath(dir)
          if (retained.none { it.startsWith(identity) }) delete(dir)
          return FileVisitResult.CONTINUE
        }

        private fun delete(target: Path) {
          if (Files.deleteIfExists(target)) removed.add(target)
        }
      },
    )
    return removed
  }
}
