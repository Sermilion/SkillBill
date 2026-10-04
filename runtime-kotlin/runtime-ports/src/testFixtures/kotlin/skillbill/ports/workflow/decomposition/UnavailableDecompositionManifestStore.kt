package skillbill.ports.workflow.decomposition

import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import java.nio.file.Path

object UnavailableDecompositionManifestStore : DecompositionManifestStore {
  override fun readText(path: Path): String = unavailableDecompositionManifestStore()

  override fun readTextWithoutRecovery(path: Path): String = unavailableDecompositionManifestStore()

  override fun isRegularFile(path: Path): Boolean = unavailableDecompositionManifestStore()

  override fun isRegularFileWithoutRecovery(path: Path): Boolean = unavailableDecompositionManifestStore()

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = unavailableDecompositionManifestStore()

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    unavailableDecompositionManifestStore()

  override fun listDirectChildDirectories(directory: Path): List<Path> = unavailableDecompositionManifestStore()

  override fun listTree(directory: Path): List<Path> = unavailableDecompositionManifestStore()

  override fun writeTextAtomically(
    target: Path,
    content: String,
  ): Unit = unavailableDecompositionManifestStore()

  override fun deleteIfExists(target: Path): Unit = unavailableDecompositionManifestStore()

  override fun encodeManifestYaml(wireMap: DecompositionManifestWireMap): String =
    unavailableDecompositionManifestStore()

  override fun <T> writeBundleAtomically(
    writes: List<Pair<Path, String>>,
    verify: () -> T,
  ): T = unavailableDecompositionManifestStore()
}

private fun unavailableDecompositionManifestStore(): Nothing {
  error("Decomposition manifest file store is not configured for this runtime.")
}
