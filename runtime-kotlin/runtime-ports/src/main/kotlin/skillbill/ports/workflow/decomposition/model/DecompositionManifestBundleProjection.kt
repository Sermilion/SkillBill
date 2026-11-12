package skillbill.ports.workflow.decomposition.model

import java.nio.file.Path

data class DecompositionManifestBundleProjection(
  val expectedBefore: List<DecompositionManifestFileIdentity>,
  val protectedFiles: List<DecompositionManifestFileIdentity>,
)

data class DecompositionManifestFileIdentity(
  val path: Path,
  val sha256: String?,
)
