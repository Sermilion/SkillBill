package skillbill.ports.blockincident

import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import java.time.Instant

interface BlockIncidentRepository {
  fun createOrLink(
    occurrence: BlockIncidentOccurrence,
    alias: BlockIncidentAlias,
    deadline: Instant,
  ): BlockIncident

  fun find(occurrenceToken: String): BlockIncident?

  fun lookup(occurrenceToken: String): BlockIncidentLookup =
    find(occurrenceToken)?.let(BlockIncidentLookup::Found) ?: BlockIncidentLookup.Absent

  fun pending(limit: Int): List<BlockIncident>

  fun claim(
    occurrenceToken: String,
    expectedRevision: Long,
    ownerToken: String,
    ownerGeneration: Long,
    hostIdentity: String,
    processId: Long,
    expiresAt: Instant,
  ): Boolean

  fun renew(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    expiresAt: Instant,
  ): Boolean

  fun settle(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): Boolean

  fun expireDeadline(
    occurrenceToken: String,
    expectedRevision: Long,
    observedOwnerToken: String?,
    observedOwnerGeneration: Long?,
  ): Boolean
}
