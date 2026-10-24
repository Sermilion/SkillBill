package skillbill.engine.goalrunner.planning.outcome

import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.projectstore.model.resolveSpecPath
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

fun canonicalRepository(
  repoRoot: Path,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path =
  repositoryEnclosingRootPort.canonicalPath(
    repositoryEnclosingRootPort.enclosingRepositoryRoot(repoRoot),
  )

fun canonicalStore(
  store: ProjectStoreLocation,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): ProjectStoreLocation =
  store.copy(
    root = repositoryEnclosingRootPort.canonicalPath(store.root),
    specsRoot = repositoryEnclosingRootPort.canonicalPath(store.specsRoot),
  )

fun resolvedGovernedPath(
  canonicalRepository: Path,
  canonicalStore: ProjectStoreLocation,
  governingPath: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path {
  val lexical = canonicalStore.resolveSpecPath(governingPath, canonicalRepository).toAbsolutePath().normalize()
  return repositoryEnclosingRootPort.optionalRealPath(lexical) ?: lexical
}

fun resolvedSubSpecPath(
  canonicalRepository: Path,
  canonicalStore: ProjectStoreLocation,
  specPath: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path? {
  if (specPath.isBlank()) return null
  val resolved = resolvedGovernedPath(canonicalRepository, canonicalStore, specPath, repositoryEnclosingRootPort)
  return resolved.takeIf { canonicalStore.classifySpecPath(it, canonicalRepository) != null }
}
