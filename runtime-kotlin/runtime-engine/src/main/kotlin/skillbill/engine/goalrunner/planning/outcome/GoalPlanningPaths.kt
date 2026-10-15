package skillbill.engine.goalrunner.planning.outcome

import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

fun canonicalRepository(
  repoRoot: Path,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path =
  repositoryEnclosingRootPort.canonicalPath(
    repositoryEnclosingRootPort.enclosingRepositoryRoot(repoRoot),
  )

fun resolvedGovernedPath(
  canonicalRepository: Path,
  governingPath: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path {
  val lexical = lexicalPath(canonicalRepository, governingPath)
  return repositoryEnclosingRootPort.optionalRealPath(lexical) ?: lexical
}

fun resolvedSubSpecPath(
  canonicalRepository: Path,
  specPath: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path? {
  if (specPath.isBlank()) return null
  val lexical = lexicalPath(canonicalRepository, specPath)
  val resolved = repositoryEnclosingRootPort.optionalRealPath(lexical) ?: lexical
  return resolved.takeIf { it.startsWith(canonicalRepository) }
}

private fun lexicalPath(
  canonicalRepository: Path,
  governingPath: String,
): Path {
  val path = Path.of(governingPath)
  return (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
}
