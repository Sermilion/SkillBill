package skillbill.engine.decomposition

import skillbill.application.decomposition.encodeValidatedDecompositionManifestYaml
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest

public fun encodeDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
  sourceLabel: String = "<in-memory>",
): String = encodeValidatedDecompositionManifestYaml(manifest, validator, fileStore, sourceLabel).yamlText
