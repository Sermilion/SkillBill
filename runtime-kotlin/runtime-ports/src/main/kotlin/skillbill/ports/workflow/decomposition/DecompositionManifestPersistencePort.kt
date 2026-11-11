package skillbill.ports.workflow.decomposition

import skillbill.ports.workflow.decomposition.model.DecompositionManifestBundleProjection
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import java.nio.file.Path

interface DecompositionManifestPersistencePort {
  fun readText(path: Path): String

  fun readTextWithoutRecovery(path: Path): String

  fun isRegularFile(path: Path): Boolean

  fun isRegularFileWithoutRecovery(path: Path): Boolean

  fun writeTextAtomically(
    target: Path,
    content: String,
  )

  fun deleteIfExists(target: Path)

  fun encodeManifestYaml(wireMap: DecompositionManifestWireMap): String

  fun <T> writeBundleAtomically(
    writes: List<Pair<Path, String>>,
    verify: () -> T,
  ): T

  fun <T> writeBundleAtomically(
    writes: List<Pair<Path, String>>,
    projection: DecompositionManifestBundleProjection,
    verify: () -> T,
  ): T
}
