package skillbill.application.decomposition.model

import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestRepairEvidence

data class LoadedDecompositionManifest(
  val manifest: DecompositionManifest,
  val yamlText: String,
  val repairEvidence: DecompositionManifestRepairEvidence?,
)

data class ValidatedDecompositionManifestYaml(
  val manifest: DecompositionManifest,
  val yamlText: String,
  val repairEvidence: DecompositionManifestRepairEvidence?,
)
