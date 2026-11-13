package skillbill.ports.system

import java.nio.file.Path

interface UninstallFileInspection {
  fun isRegularFile(path: Path): Boolean

  fun isDirectory(path: Path): Boolean

  fun readRegularFile(path: Path): String
}

interface UninstallPathsPort : UninstallFileInspection {
  fun listImmediateDirectoryNames(root: Path): List<String>

  fun exists(path: Path): Boolean

  fun isSymbolicLink(path: Path): Boolean

  fun readSymbolicLink(path: Path): Path

  fun deleteIfExists(path: Path): Boolean

  fun removeTree(path: Path): List<Path>

  fun <T> withConfigTransaction(
    userHome: Path,
    environment: Map<String, String>,
    action: () -> T,
  ): T

  fun configLockFilePaths(
    userHome: Path,
    environment: Map<String, String>,
  ): List<Path>

  fun removeTreeExcept(
    path: Path,
    retainedPaths: Collection<Path>,
  ): List<Path>
}
