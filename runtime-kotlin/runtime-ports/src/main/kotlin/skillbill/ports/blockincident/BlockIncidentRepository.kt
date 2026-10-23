package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.BlockIncidentLookup
import skillbill.ports.blockincident.model.BlockedWorkflowWithoutIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.StandalonePhaseIncidentAlias
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

interface BlockIncidentDiagnosisReads {
  /** Returns the valid diagnosis records stored for the incident, oldest revision first. Corrupt rows are skipped. */
  fun diagnoses(occurrenceToken: String): List<BlockIncidentDiagnosisRecord>
}

interface BlockIncidentRepository :
  BlockIncidentRolloutReads,
  BlockIncidentDiagnosisReads,
  BlockIncidentHistoryReads,
  BlockIncidentRecoveryWrites {
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

  /**
   * Settles the claimed incident like [settle] and stores [record] in the same transaction. The stored record takes
   * the incident revision current at settle time, because claim renewals advance the revision after the record was
   * drafted. Returns false and stores nothing when the owner, generation, or claim deadline no longer allow the
   * settle. Throws when the record fails validation.
   */
  fun settleWithDiagnosis(
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    record: BlockIncidentDiagnosisRecord,
    nextEligibleAt: Instant?,
  ): Boolean

  fun expireDeadline(
    occurrenceToken: String,
    expectedRevision: Long,
    observedOwnerToken: String?,
    observedOwnerGeneration: Long?,
  ): Boolean
}

/** Records the recovery summary without changing occurrence or diagnosis. */
interface BlockIncidentRecoveryWrites {
  fun recordRecoveryOutcome(
    occurrenceToken: String,
    expectedRevision: Long,
    recovery: BlockIncidentRecovery,
  ): Boolean
}

interface BlockIncidentHistoryReads {
  /**
   * Lists incidents associated with the workflow, its parent workflow, or the invocation. Invocation association
   * includes original occurrence coordinates and durable standalone phase aliases identified by
   * [StandalonePhaseIncidentAlias]. Associations are deduplicated before the global newest-first limit. Mutable
   * status, accepted references, recovery eligibility, and current registration do not affect retained history.
   */
  fun listForWork(
    workflowId: String?,
    invocationId: String?,
    limit: Int,
  ): List<BlockIncident>
}
