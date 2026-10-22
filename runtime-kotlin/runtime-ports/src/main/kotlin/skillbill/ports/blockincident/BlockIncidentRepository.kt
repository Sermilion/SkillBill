package skillbill.ports.blockincident

import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import java.time.Instant

interface BlockIncidentRepository {
  /** Returns the incident already associated with this alias, if one exists. */
  fun findByAlias(alias: BlockIncidentAlias): BlockIncident?

  fun rolloutActivatedAt(): Instant? = null

  fun latestForWorkflow(workflowId: String): BlockIncident?


  fun blockedWithoutIncident(since: Instant, limit: Int): List<BlockedWorkflowWithoutIncident> = emptyList()

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

data class BlockedWorkflowWithoutIncident(
  val workflowId: String,
  val parentWorkflowId: String?,
  val kind: Kind,
  val blockedReason: String,
  val phaseId: String?,
  val attempt: Int?,
  val updatedAt: Instant,
) {
  enum class Kind { GOAL, PHASE }
}
