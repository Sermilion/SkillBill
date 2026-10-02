package skillbill.workflow.decomposition.runtime

import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionManifestProjectionFailurePayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE_ARTIFACT_KEY
import skillbill.workflow.engine.model.DurableWorkflowArtifacts

internal data class DecompositionManifestProjectionFailureArtifact(
  val operation: String,
  val targetPath: String,
)

internal fun DurableWorkflowArtifacts.decompositionManifestProjectionFailure():
  DecompositionManifestProjectionFailureArtifact? {
  if (!containsKey(DECOMPOSITION_MANIFEST_PROJECTION_FAILURE_ARTIFACT_KEY)) return null
  val raw =
    JsonCodec.anyToStringAnyMap(this[DECOMPOSITION_MANIFEST_PROJECTION_FAILURE_ARTIFACT_KEY])
      ?: throw InvalidWorkflowStateSchemaError(
        "Decomposition manifest projection failure must decode to an object.",
      )
  val operation = raw.requiredFailureField(DecompositionManifestProjectionFailurePayloadKeys.OPERATION, "operation")
  val targetPath =
    raw.requiredFailureField(
      DecompositionManifestProjectionFailurePayloadKeys.TARGET_PATH,
      "target path",
    )
  return DecompositionManifestProjectionFailureArtifact(operation, targetPath)
}

private fun Map<String, Any?>.requiredFailureField(
  key: String,
  label: String,
): String =
  (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: throw InvalidWorkflowStateSchemaError(
      "Decomposition manifest projection failure $label is required.",
    )
