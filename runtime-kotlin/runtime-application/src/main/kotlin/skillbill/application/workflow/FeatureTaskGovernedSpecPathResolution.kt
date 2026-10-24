package skillbill.application.workflow

import skillbill.application.workflow.model.FeatureTaskGovernedSpecPathResult
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

fun resolveFeatureTaskGovernedSpecPath(
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  projectStorePort: ProjectStorePort,
  repositoryRoot: Path,
  specPath: Path,
): FeatureTaskGovernedSpecPathResult {
  val root =
    repositoryEnclosingRootPort.realPathThroughExistingAncestor(
      repositoryEnclosingRootPort.enclosingRepositoryRoot(repositoryRoot),
    )
  val store = projectStorePort.resolve(repositoryRoot).canonicalSpecsRoot(repositoryEnclosingRootPort)
  val absolute =
    when {
      specPath.isAbsolute -> specPath
      specPath.startsWith(ProjectStoreLayout.SPECS_DIRECTORY) -> store.root.resolve(specPath)
      else -> root.resolve(specPath)
    }
  val resolved = repositoryEnclosingRootPort.realPathThroughExistingAncestor(absolute)
  val relative =
    store.classifySpecPath(resolved, root)?.relative
      ?: return FeatureTaskGovernedSpecPathResult.OutsideRepository(root)
  if (!validGovernedSpecPath(relative)) {
    return FeatureTaskGovernedSpecPathResult.InvalidGovernedPath
  }
  return FeatureTaskGovernedSpecPathResult.Ok(relative)
}

private fun ProjectStoreLocation.canonicalSpecsRoot(port: RepositoryEnclosingRootPort): ProjectStoreLocation =
  copy(
    root = port.realPathThroughExistingAncestor(root),
    specsRoot = port.realPathThroughExistingAncestor(specsRoot),
  )

private fun RepositoryEnclosingRootPort.realPathThroughExistingAncestor(path: Path): Path {
  val absolute = canonicalPath(path)
  return generateSequence(absolute) { it.parent }
    .firstNotNullOfOrNull { ancestor -> optionalRealPath(ancestor)?.resolve(ancestor.relativize(absolute)) }
    ?: absolute
}

private fun validGovernedSpecPath(value: String): Boolean =
  (
    value.startsWith(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX) ||
      value.startsWith(ProjectStoreLayout.SPECS_PREFIX)
  ) &&
    value.endsWith(".md") &&
    value.none { it == '\r' || it == '\n' } &&
    value.split('/').none { it.isBlank() || it == "." || it == ".." }
