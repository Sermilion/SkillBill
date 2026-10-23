package skillbill.infrastructure.workflow.decomposition

import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.specBundleRoots
import skillbill.ports.workflow.decomposition.DecompositionManifestDiscoveryPort
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class FileSystemDecompositionManifestFileStoreDiscovery(
  private val projectStore: ProjectStorePort,
  private val bundleJournal: DecompositionManifestBundleJournal,
) : DecompositionManifestDiscoveryPort {
  override fun listDirectChildDirectories(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.list(directory).use { paths ->
      paths.filter { path -> Files.isDirectory(path) }.toList()
    }
  }

  override fun listTree(directory: Path): List<Path> = Files.walk(directory).use { paths -> paths.toList() }

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> =
    findManifests(repoRoot, recoverPending = true)

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    findManifests(repoRoot, recoverPending = false)

  private fun findManifests(
    repoRoot: Path,
    recoverPending: Boolean,
  ): List<Path> {
    val specsRoots = projectStore.resolve(repoRoot).specBundleRoots(repoRoot)
    return specsRoots.flatMap { specsRoot -> manifestsUnder(specsRoot, recoverPending) }
  }

  private fun manifestsUnder(
    specsRoot: Path,
    recoverPending: Boolean,
  ): List<Path> {
    if (!Files.isDirectory(specsRoot)) return emptyList()
    val archivedRoot = specsRoot.resolve("done")
    val manifests = mutableListOf<Path>()
    Files.walkFileTree(
      specsRoot,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(
          dir: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          if (dir == archivedRoot) return FileVisitResult.SKIP_SUBTREE
          if (recoverPending) bundleJournal.recoverPending(dir) else bundleJournal.failIfPending(dir)
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(
          file: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          if (Files.isRegularFile(file) && file.fileName.toString() == "decomposition-manifest.yaml") {
            manifests.add(file)
          }
          return FileVisitResult.CONTINUE
        }
      },
    )
    return manifests
  }
}
