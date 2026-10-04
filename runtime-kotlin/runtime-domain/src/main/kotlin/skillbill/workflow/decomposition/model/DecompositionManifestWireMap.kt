package skillbill.workflow.decomposition.model

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError

class DecompositionManifestWireMap private constructor(
  private val delegate: Map<String, Any?>,
) : Map<String, Any?> by delegate {
  override fun equals(other: Any?): Boolean = other is DecompositionManifestWireMap && delegate == other.delegate

  override fun hashCode(): Int = delegate.hashCode()

  companion object {
    fun from(map: Map<String, Any?>): DecompositionManifestWireMap = DecompositionManifestWireMap(LinkedHashMap(map))

    fun fromAny(raw: Any?): DecompositionManifestWireMap =
      from(
        JsonCodec.anyToStringAnyMap(raw)
          ?: throw invalidWorkflowStateSchemaError("Decomposition manifest wire map must decode to an object."),
      )
  }
}
