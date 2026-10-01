package skillbill.ports.workflow.decomposition

import java.nio.file.Path

interface DecompositionManifestDiscoveryPort {
  fun findDecompositionManifestFiles(repoRoot: Path): List<Path>

  fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path>

  fun listDirectChildDirectories(directory: Path): List<Path>

  fun listTree(directory: Path): List<Path>
}

interface DecompositionManifestStore :
  DecompositionManifestPersistencePort,
  DecompositionManifestDiscoveryPort
