package skillbill.infrastructure.workflow.decomposition

import skillbill.ports.workflow.decomposition.DecompositionManifestDiscoveryPort
import java.nio.file.Files
import java.nio.file.Path

internal class FileSystemDecompositionManifestFileStoreDiscovery(
  private val bundleJournal: DecompositionManifestBundleJournal,
) : DecompositionManifestDiscoveryPort {
  override fun listDirectChildDirectories(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.list(directory).use { paths ->
      paths.filter { path -> Files.isDirectory(path) }.toList()
    }
  }

  override fun listTree(directory: Path): List<Path> = Files.walk(directory).use { paths -> paths.toList() }

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> {
    val featureSpecsRoot = repoRoot.resolve(".feature-specs")
    if (!Files.isDirectory(featureSpecsRoot)) return emptyList()
    Files.walk(featureSpecsRoot).use { paths ->
      paths.filter { path -> Files.isDirectory(path) }.forEach(bundleJournal::recoverPending)
    }
    return Files.walk(featureSpecsRoot).use { paths ->
      paths
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == "decomposition-manifest.yaml" }
        .toList()
    }
  }

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> {
    val featureSpecsRoot = repoRoot.resolve(".feature-specs")
    if (!Files.isDirectory(featureSpecsRoot)) return emptyList()
    bundleJournal.failIfPendingUnder(featureSpecsRoot)
    return Files.walk(featureSpecsRoot).use { paths ->
      paths
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == "decomposition-manifest.yaml" }
        .toList()
    }
  }
}
