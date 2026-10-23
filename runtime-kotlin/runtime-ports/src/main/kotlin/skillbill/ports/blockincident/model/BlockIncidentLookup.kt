package skillbill.ports.blockincident.model

import skillbill.workflow.taskruntime.model.blockincident.BlockIncident

/** Typed outcome of reading one incident, so a corrupt row is reported instead of failing the caller. */
sealed interface BlockIncidentLookup {
  data class Found(val incident: BlockIncident) : BlockIncidentLookup

  data object Absent : BlockIncidentLookup

  /**
   * The stored row failed validation. [durable] is false when the read ran on a read-only session and the
   * quarantine row is still pending a writable pass.
   */
  data class Quarantined(
    val occurrenceToken: String,
    val failureCode: String,
    val durable: Boolean,
  ) : BlockIncidentLookup
}
