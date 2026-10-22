package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.BlockIncidentLookup
import skillbill.ports.blockincident.model.BlockedWorkflowWithoutIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import java.time.Instant

/** Reads the rollout marker and the blocked workflows that never received an incident after rollout. */
interface BlockIncidentRolloutReads {
  /** Returns when block incident capture was activated, or null before the rollout migration ran. */
  fun rolloutActivatedAt(): Instant?

  /**
   * Returns up to [limit] goal and phase workflows that are blocked now, entered that block and were updated at or
   * after [since], and have no incident linked to that block, oldest update first. A workflow whose persisted block
   * identity applies to its current step is excluded once that identity's alias is linked; a workflow without an
   * applicable identity is excluded once any incident for it was observed since the block was entered.
   */
  fun blockedWithoutIncident(
    since: Instant,
    limit: Int,
  ): List<BlockedWorkflowWithoutIncident>
}

interface BlockIncidentRepository : BlockIncidentRolloutReads {
  /** Returns the incident already associated with this alias, if one exists. */
  fun findByAlias(alias: BlockIncidentAlias): BlockIncident?

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
    claim: BlockIncidentClaim,
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
