package skillbill.application.decomposition

import java.nio.file.Path

fun resolvedParentSpecPath(
  repoRoot: Path,
  parentSpecPath: Path,
): Path = if (parentSpecPath.isAbsolute) parentSpecPath.normalize() else repoRoot.resolve(parentSpecPath).normalize()

fun repoRelativePath(
  repoRoot: Path,
  path: Path,
): String {
  val root = repoRoot.toAbsolutePath().normalize()
  val absolute = resolvedParentSpecPath(root, path).toAbsolutePath().normalize()
  if (!absolute.startsWith(root)) {
    return absolute.toString()
  }
  return root.relativize(absolute).joinToString("/")
}
