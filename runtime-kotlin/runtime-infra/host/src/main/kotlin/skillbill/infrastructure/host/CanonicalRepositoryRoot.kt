package skillbill.infrastructure.host

import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Files
import java.nio.file.Path

object CanonicalRepositoryRoot : RepositoryEnclosingRootPort {
  override fun enclosingRepositoryRoot(start: Path): Path {
    val resolvedStart = canonicalPath(start)
    var candidate = resolvedStart
    while (!candidate.resolve(".git").toFile().exists()) {
      candidate = candidate.parent ?: return resolvedStart
    }
    return canonicalPath(candidate)
  }

  override fun canonicalPath(path: Path): Path =
    runCatching { path.toAbsolutePath().normalize().toRealPath() }
      .getOrElse { path.toAbsolutePath().normalize() }

  override fun optionalRealPath(path: Path): Path? =
    if (Files.exists(path)) {
      runCatching { path.toRealPath() }.getOrNull()
    } else {
      null
    }

  override fun repositoryIdentity(repoRoot: Path): String {
    val gitRoot = canonicalPath(enclosingRepositoryRoot(repoRoot))
    return FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX + gitRoot
  }

  override fun primaryRepositoryRoot(repoRoot: Path): Path {
    val root = canonicalPath(repoRoot)
    val marker = root.resolve(GIT_MARKER)
    val common = (if (Files.isRegularFile(marker)) linkedWorktreeCommonDir(root, marker) else null) ?: return root
    val primary = if (common.fileName?.toString() == GIT_MARKER) common.parent else common
    return primary?.let(::canonicalPath) ?: root
  }

  private fun linkedWorktreeCommonDir(
    root: Path,
    marker: Path,
  ): Path? {
    val gitDir =
      readTrimmed(marker)
        ?.lineSequence()
        ?.firstOrNull { it.startsWith(GITDIR_PREFIX) }
        ?.removePrefix(GITDIR_PREFIX)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { root.resolve(it).normalize() }
        ?: return null
    val commonDir = readTrimmed(gitDir.resolve(COMMONDIR_FILE))?.takeIf { it.isNotEmpty() }
    return commonDir?.let { gitDir.resolve(it).normalize() } ?: gitDir
  }

  private fun readTrimmed(path: Path): String? = runCatching { Files.readString(path).trim() }.getOrNull()

  private const val GIT_MARKER = ".git"
  private const val GITDIR_PREFIX = "gitdir:"
  private const val COMMONDIR_FILE = "commondir"
}
