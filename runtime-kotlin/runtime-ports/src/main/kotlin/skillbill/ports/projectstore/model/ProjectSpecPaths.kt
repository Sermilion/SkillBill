package skillbill.ports.projectstore.model

import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.ProjectStoreLayout
import java.nio.file.Path

fun ProjectStoreLocation.specBundleRoots(checkoutRoot: Path): List<Path> =
  listOf(specsRoot, checkoutRoot.resolve(LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY))

fun ProjectStoreLocation.classifySpecPath(
  absolutePath: Path,
  checkoutRoot: Path,
): SpecPathLocation? {
  val legacyRoot = specBundleRoots(checkoutRoot).last()
  return when {
    absolutePath.startsWith(specsRoot) ->
      SpecPathLocation.Store(ProjectStoreLayout.SPECS_PREFIX + specsRoot.relativize(absolutePath).toSlashPath())
    absolutePath.startsWith(legacyRoot) ->
      SpecPathLocation.Legacy(
        LegacyProjectStatePaths.FEATURE_SPECS_PREFIX + legacyRoot.relativize(absolutePath).toSlashPath(),
      )
    else -> null
  }
}

fun ProjectStoreLocation.resolveSpecPath(
  specPath: String,
  checkoutRoot: Path,
): Path = resolveSpecPath(root, checkoutRoot, specPath)

fun resolveSpecPath(
  storeRoot: Path,
  checkoutRoot: Path,
  specPath: String,
): Path {
  val path = Path.of(specPath)
  return when {
    path.isAbsolute -> path
    specPath.startsWith(ProjectStoreLayout.SPECS_PREFIX) -> storeRoot.resolve(specPath)
    else -> checkoutRoot.resolve(specPath)
  }
}

private fun Path.toSlashPath(): String = joinToString("/") { it.toString() }
