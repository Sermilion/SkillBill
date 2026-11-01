package skillbill.infrastructure.workflow.decomposition

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestDiscoveryPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

@Inject
class FileSystemDecompositionManifestFileStore(
  projectStore: ProjectStorePort,
) : DecompositionManifestStore,
  DecompositionManifestDiscoveryPort by FileSystemDecompositionManifestFileStoreDiscovery(
    projectStore,
    DecompositionManifestBundleJournal(),
  ) {
  private val bundleJournal = DecompositionManifestBundleJournal()
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }

  override fun readText(path: Path): String {
    return withDecompositionManifestBundleLock(path.parent, bundleJournal.hostPlatform) {
      bundleJournal.recoverPendingUnlocked(path.parent)
      Files.readString(path)
    }
  }

  override fun readTextWithoutRecovery(path: Path): String {
    bundleJournal.failIfPending(path.parent)
    return Files.readString(path)
  }

  override fun isRegularFile(path: Path): Boolean {
    return withDecompositionManifestBundleLock(path.parent, bundleJournal.hostPlatform) {
      bundleJournal.recoverPendingUnlocked(path.parent)
      Files.isRegularFile(path)
    }
  }

  override fun isRegularFileWithoutRecovery(path: Path): Boolean {
    bundleJournal.failIfPending(path.parent)
    return Files.isRegularFile(path)
  }

  override fun <T> writeBundleAtomically(
    writes: List<Pair<Path, String>>,
    verify: () -> T,
  ): T {
    val distinctWrites = writes.distinctBy { (path, _) -> path.toAbsolutePath().normalize() }
    val parents = distinctWrites.map { (path, _) -> path.toAbsolutePath().normalize().parent }.distinct()
    if (parents.size != 1 || distinctWrites.isEmpty()) {
      val snapshots =
        writes.distinctBy { (path, _) -> path }.map { (path, _) ->
          val existed = isRegularFile(path)
          DecompositionManifestBundleSnapshot(path, existed, if (existed) readText(path) else null)
        }
      return restoringOnFailure(
        restore = { failure ->
          snapshots.asReversed().forEach { snapshot ->
            catchingNonCancellation {
              if (snapshot.existed) {
                writeTextAtomically(snapshot.path, requireNotNull(snapshot.content))
              } else {
                deleteIfExists(snapshot.path)
              }
            }.onFailure(failure::addSuppressed)
          }
        },
      ) {
        writes.forEach { (path, content) -> writeTextAtomically(path, content) }
        verify()
      }
    }

    val parent = requireNotNull(parents.single())
    return withDecompositionManifestBundleLock(parent, bundleJournal.hostPlatform) {
      bundleJournal.recoverPendingUnlocked(parent)
      val snapshots =
        distinctWrites.map { (path, _) ->
          val normalized = path.toAbsolutePath().normalize()
          val existed = Files.isRegularFile(normalized)
          DecompositionManifestBundleSnapshot(normalized, existed, if (existed) Files.readString(normalized) else null)
        }
      val transaction = bundleJournal.create(parent, distinctWrites)
      restoringOnFailure(
        restore = { failure ->
          snapshots.asReversed().forEach { snapshot ->
            catchingNonCancellation {
              if (snapshot.existed) {
                bundleJournal.writeAtomically(snapshot.path, requireNotNull(snapshot.content))
              } else {
                Files.deleteIfExists(snapshot.path)
              }
            }.onFailure(failure::addSuppressed)
          }
          catchingNonCancellation { cleanupValidatedBundleJournal(transaction) }.onFailure(failure::addSuppressed)
        },
      ) {
        bundleJournal.apply(transaction)
        val result = verify()
        bundleJournal.cleanup(transaction)
        result
      }
    }
  }

  override fun encodeManifestYaml(wireMap: DecompositionManifestWireMap): String =
    yamlMapper.writeValueAsString(wireMap)

  override fun deleteIfExists(target: Path) {
    withDecompositionManifestBundleLock(target.parent, bundleJournal.hostPlatform) {
      bundleJournal.recoverPendingUnlocked(target.parent)
      Files.deleteIfExists(target)
    }
  }

  override fun writeTextAtomically(
    target: Path,
    content: String,
  ) {
    withDecompositionManifestBundleLock(target.parent, bundleJournal.hostPlatform) {
      bundleJournal.recoverPendingUnlocked(target.parent)
      bundleJournal.writeAtomically(target, content)
    }
  }
}

private inline fun <T> restoringOnFailure(
  restore: (Throwable) -> Unit,
  block: () -> T,
): T =
  try {
    catchingNonCancellation(block).getOrElse { failure ->
      restore(failure)
      throw failure
    }
  } catch (interrupted: InterruptedException) {
    restore(interrupted)
    throw interrupted
  } catch (cancelled: CancellationException) {
    restore(cancelled)
    throw cancelled
  }
