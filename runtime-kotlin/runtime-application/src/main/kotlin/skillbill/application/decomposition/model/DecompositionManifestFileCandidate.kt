package skillbill.application.decomposition.model

import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

data class DecompositionManifestFileCandidate(
  val path: Path,
  val manifest: DecompositionManifest,
)
