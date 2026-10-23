package skillbill.application.decomposition

import skillbill.ports.projectstore.model.ProjectStoreLocation
import java.nio.file.Path

const val DECOMPOSITION_MANIFEST_FILENAME: String = "decomposition-manifest.yaml"

fun decompositionManifestPath(
  repoRoot: Path,
  store: ProjectStoreLocation,
  parentSpecPath: Path,
  subtaskSpecPaths: List<String>,
): Path =
  decompositionManifestDirectory(repoRoot, store, parentSpecPath, subtaskSpecPaths)
    .resolve(DECOMPOSITION_MANIFEST_FILENAME)

internal fun decompositionManifestDirectory(
  repoRoot: Path,
  store: ProjectStoreLocation,
  parentSpecPath: Path,
  subtaskSpecPaths: List<String>,
): Path {
  val parentDirectory = resolvedParentSpecPath(repoRoot, parentSpecPath, store).parent
  val subtaskDirectories =
    subtaskSpecPaths
      .map { resolvedParentSpecPath(repoRoot, Path.of(it), store).parent }
      .distinct()
  return subtaskDirectories.singleOrNull() ?: parentDirectory
}
