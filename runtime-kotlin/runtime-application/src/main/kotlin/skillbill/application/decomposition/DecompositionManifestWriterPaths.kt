package skillbill.application.decomposition

import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.projectstore.model.resolveSpecPath
import java.nio.file.Path

fun resolvedParentSpecPath(
  repoRoot: Path,
  parentSpecPath: Path,
  store: ProjectStoreLocation,
): Path = store.resolveSpecPath(parentSpecPath.toString(), repoRoot).normalize()

fun repoRelativePath(
  repoRoot: Path,
  store: ProjectStoreLocation,
  path: Path,
): String {
  val checkout = repoRoot.toAbsolutePath().normalize()
  val location = path.takeIf(Path::isAbsolute)?.let { store.classifySpecPath(it.normalize(), checkout) }
  return location?.relative ?: repoRelativePath(repoRoot, path)
}

fun repoRelativePath(
  repoRoot: Path,
  path: Path,
): String {
  if (!path.isAbsolute) return path.normalize().joinToString("/")
  val root = repoRoot.toAbsolutePath().normalize()
  val absolute = path.toAbsolutePath().normalize()
  if (!absolute.startsWith(root)) {
    return absolute.toString()
  }
  return root.relativize(absolute).joinToString("/")
}
