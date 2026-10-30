package skillbill.ports.workflow

import skillbill.workflow.engine.model.DurableWorkflowArtifacts

/**
 * Wire form of a whole durable-artifacts map, for callers outside the persistence adapter that must emit or digest
 * it. Values of `DurableWorkflowArtifactFamily.TYPED_RECORD_FAMILIES` hold typed records in memory; only the adapter
 * knows their JSON shape, so these callers go through this port instead of walking the map themselves.
 */
interface WorkflowArtifactWireEncoder {
  /** The full JSON text: family values through the adapter's codecs, every other value as plain JSON. */
  fun encode(artifacts: DurableWorkflowArtifacts): String

  /** Inverse of [encode]: family values decode into typed records or an `UndecodedTypedArtifact`. */
  fun decode(wire: String): DurableWorkflowArtifacts
}
