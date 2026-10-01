package skillbill.workflow.decomposition.runtime

import skillbill.workflow.decomposition.DecompositionManifestWireCodec
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.engine.model.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.workflow.engine.model.DurableWorkflowArtifacts

fun DurableWorkflowArtifacts.hasDecompositionRuntimeArtifact(): Boolean =
  containsKey(DECOMPOSITION_RUNTIME_ARTIFACT_KEY)

fun DurableWorkflowArtifacts.decompositionRuntime(): DecompositionManifest? {
  if (!containsKey(DECOMPOSITION_RUNTIME_ARTIFACT_KEY)) return null
  return DecompositionManifestWireCodec.decode(
    DecompositionManifestWireMap.fromAny(this[DECOMPOSITION_RUNTIME_ARTIFACT_KEY]),
    DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
  )
}
