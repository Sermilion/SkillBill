package skillbill.application.review.spec

import skillbill.application.decomposition.repoRelativePath
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.classifySpecPath
import java.nio.file.Path

internal fun specProvenancePath(
  repoRoot: Path,
  path: Path,
  store: ProjectStoreLocation,
): String {
  val root = repoRoot.toAbsolutePath().normalize()
  val absolute = path.toAbsolutePath().normalize()
  return store.classifySpecPath(absolute, root)?.relative ?: repoRelativePath(repoRoot, absolute)
}
