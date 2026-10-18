package skillbill.application

import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Files
import java.nio.file.Path

object TestRepositoryEnclosingRoot : RepositoryEnclosingRootPort {
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

  override fun primaryRepositoryRoot(repoRoot: Path): Path = canonicalPath(repoRoot)
}
