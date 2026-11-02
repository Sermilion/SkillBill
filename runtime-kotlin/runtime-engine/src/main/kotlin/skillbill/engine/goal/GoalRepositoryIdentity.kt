package skillbill.engine.goal

import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

public fun goalRepositoryIdentity(
  repoRoot: Path,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): String = repositoryEnclosingRootPort.primaryRepositoryIdentity(repoRoot)

public fun goalRepositoryIdentityCandidates(
  repoRoot: Path,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): List<String> =
  listOf(
    repositoryEnclosingRootPort.primaryRepositoryIdentity(repoRoot),
    repositoryEnclosingRootPort.repositoryIdentity(repoRoot),
  ).distinct()

public fun goalRepositoryIdentityFilters(
  repoRoot: Path?,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): List<String?> = repoRoot?.let { goalRepositoryIdentityCandidates(it, repositoryEnclosingRootPort) } ?: listOf(null)
