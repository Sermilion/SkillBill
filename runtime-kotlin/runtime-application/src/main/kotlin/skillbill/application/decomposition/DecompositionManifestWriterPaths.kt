package skillbill.application.decomposition

import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionManifest
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

internal fun DecompositionManifest.atExistingSpecBundle(
  repoRoot: Path,
  fileStore: DecompositionManifestStore,
): DecompositionManifest {
  val activeRoot = repoRoot.toAbsolutePath().normalize().resolve(".feature-specs")
  val parent = resolvedParentSpecPath(repoRoot, Path.of(parentSpecPath)).toAbsolutePath().normalize()
  val doneRoot = activeRoot.resolve("done")
  if (!parent.startsWith(activeRoot) || parent.startsWith(doneRoot) || fileStore.isRegularFile(parent)) return this
  val archivedParent = doneRoot.resolve(activeRoot.relativize(parent))
  if (!fileStore.isRegularFile(archivedParent)) return this

  fun archivedPath(specPath: String): String {
    val path = resolvedParentSpecPath(repoRoot, Path.of(specPath)).toAbsolutePath().normalize()
    return if (path.startsWith(parent.parent)) {
      repoRelativePath(repoRoot, archivedParent.parent.resolve(parent.parent.relativize(path)))
    } else {
      specPath
    }
  }
  return copy(
    parentSpecPath = repoRelativePath(repoRoot, archivedParent),
    subtasks = subtasks.map { it.copy(specPath = archivedPath(it.specPath)) },
  )
}
