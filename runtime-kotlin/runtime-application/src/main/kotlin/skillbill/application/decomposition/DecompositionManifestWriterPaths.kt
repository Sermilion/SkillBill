package skillbill.application.decomposition

import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.projectstore.model.resolveSpecPath
import skillbill.ports.projectstore.model.specBundleRoots
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

private const val ARCHIVE_DIRECTORY_NAME = "done"

fun resolvedParentSpecPath(
  repoRoot: Path,
  parentSpecPath: Path,
  store: ProjectStoreLocation,
): Path = store.resolveSpecPath(parentSpecPath.toString(), repoRoot).normalize()

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

internal fun DecompositionManifest.atExistingSpecBundle(
  repoRoot: Path,
  store: ProjectStoreLocation,
  fileStore: DecompositionManifestStore,
): DecompositionManifest {
  val checkout = repoRoot.toAbsolutePath().normalize()
  val parent = resolvedParentSpecPath(checkout, Path.of(parentSpecPath), store).toAbsolutePath().normalize()
  val activeRoot =
    store.specBundleRoots(checkout)
      .map { it.toAbsolutePath().normalize() }
      .firstOrNull { root -> parent.startsWith(root) && !parent.startsWith(root.resolve(ARCHIVE_DIRECTORY_NAME)) }
      ?: return this
  val doneRoot = activeRoot.resolve(ARCHIVE_DIRECTORY_NAME)
  if (fileStore.isRegularFile(parent)) return this
  val archivedParent = doneRoot.resolve(activeRoot.relativize(parent))
  if (!fileStore.isRegularFile(archivedParent)) return this

  fun archivedLabel(archived: Path): String =
    store.classifySpecPath(archived, checkout)?.relative ?: repoRelativePath(checkout, archived)

  fun archivedPath(specPath: String): String {
    val path = resolvedParentSpecPath(checkout, Path.of(specPath), store).toAbsolutePath().normalize()
    return if (path.startsWith(parent.parent)) {
      archivedLabel(archivedParent.parent.resolve(parent.parent.relativize(path)))
    } else {
      specPath
    }
  }
  return copy(
    parentSpecPath = archivedLabel(archivedParent),
    subtasks = subtasks.map { it.copy(specPath = archivedPath(it.specPath)) },
  )
}
