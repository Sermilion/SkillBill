package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.BlockedWorkflowWithoutIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOwner
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReport
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentState
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitionDecision
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitions
import skillbill.workflow.taskruntime.model.blockincident.validation
import java.time.Clock
import java.time.Instant

class InMemoryBlockIncidentRepository(private val clock: Clock = Clock.systemUTC()) : BlockIncidentRepository {
  private val incidents = linkedMapOf<String, BlockIncident>()

  private val aliases = linkedMapOf<Pair<String, String>, String>()
  private val diagnosisRecords = linkedMapOf<String, MutableList<BlockIncidentDiagnosisRecord>>()
  private val blockedWorkflows = linkedMapOf<String, Pair<BlockedWorkflowWithoutIncident, BlockIncidentAlias?>>()

  var activatedAt: Instant? = null

  override fun rolloutActivatedAt(): Instant? = activatedAt

  override fun findByAlias(alias: BlockIncidentAlias): BlockIncident? =
    synchronized(this) {
      aliases[alias.kind.wireValue to alias.value]?.let(incidents::get)
    }

  fun latestForWorkflow(workflowId: String): BlockIncident? =
    synchronized(this) {
      incidents.values.filter { it.occurrence.workflowId == workflowId }
        .maxByOrNull { it.occurrence.observedAt }
    }

  override fun blockedWithoutIncident(
    since: Instant,
    limit: Int,
  ): List<BlockedWorkflowWithoutIncident> =
    synchronized(this) {
      require(limit in 1..16)
      blockedWorkflows.values
        .filter { (workflow, identityAlias) ->
          workflow.blockEnteredAt >= since && workflow.updatedAt >= since &&
            if (identityAlias != null) {
              aliases[identityAlias.kind.wireValue to identityAlias.value] == null
            } else {
              incidents.values.none {
                it.occurrence.workflowId == workflow.workflowId &&
                  it.occurrence.observedAt >= workflow.blockEnteredAt.minusSeconds(OBSERVATION_SKEW_SECONDS)
              }
            }
        }
        .map { it.first }
        .sortedWith(compareBy({ it.updatedAt }, { it.workflowId }))
        .take(limit)
    }

  fun recordBlockedWorkflow(
    workflow: BlockedWorkflowWithoutIncident,
    identityAlias: BlockIncidentAlias? = null,
  ) = synchronized(this) {
    blockedWorkflows[workflow.workflowId] = workflow to identityAlias
  }

  override fun createOrLink(
    occurrence: BlockIncidentOccurrence,
    alias: BlockIncidentAlias,
    deadline: Instant,
  ): BlockIncident =
    synchronized(this) {
      aliases[alias.kind.wireValue to alias.value]?.let(incidents::get)?.let { return it }
      incidents[occurrence.occurrenceToken]?.let { existing ->
        val linked = existing.copy(aliases = existing.aliases + alias)
        incidents[occurrence.occurrenceToken] = linked
        aliases[alias.kind.wireValue to alias.value] = occurrence.occurrenceToken
        return linked
      }
      val incident =
        BlockIncident(
          occurrence,
          listOf(alias),
          BlockIncidentState(
            BlockIncidentDiagnosis.PENDING,
            BlockIncidentRecovery.UNAVAILABLE,
            BlockIncidentReport.UNAVAILABLE,
            1,
            deadline,
            0,
            null,
            null,
          ),
        )
      incidents[occurrence.occurrenceToken] = incident
      aliases[alias.kind.wireValue to alias.value] = occurrence.occurrenceToken
      incident
    }

  override fun find(occurrenceToken: String): BlockIncident? = synchronized(this) { incidents[occurrenceToken] }

  override fun pending(limit: Int): List<BlockIncident> =
    synchronized(this) {
      incidents.values.filter { it.state.diagnosis == BlockIncidentDiagnosis.PENDING }.take(limit)
    }

  override fun claim(
    occurrenceToken: String,
    expectedRevision: Long,
    claim: BlockIncidentClaim,
  ): Boolean =
    synchronized(this) {
      val current = incidents[occurrenceToken] ?: return false
      val candidate =
        when (
          val decision =
            BlockIncidentTransitions.claim(
              current,
              clock.instant(),
              expectedRevision,
              claim,
            )
        ) {
          is BlockIncidentTransitionDecision.Accepted -> decision.candidate
          is BlockIncidentTransitionDecision.Refused -> return false
        }
      incidents[occurrenceToken] = candidate
      true
    }

  override fun renew(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    expiresAt: Instant,
  ): Boolean =
    synchronized(this) {
      val current = incidents[occurrenceToken] ?: return false
      val candidate =
        when (
          val decision =
            BlockIncidentTransitions.renew(
              current,
              clock.instant(),
              BlockIncidentOwner(ownerToken, ownerGeneration),
              expiresAt,
            )
        ) {
          is BlockIncidentTransitionDecision.Accepted -> decision.candidate
          is BlockIncidentTransitionDecision.Refused -> return false
        }
      incidents[occurrenceToken] = candidate
      true
    }

  override fun settle(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): Boolean =
    synchronized(this) {
      val candidate = settledCandidate(occurrenceToken, ownerToken, ownerGeneration, diagnosis, nextEligibleAt)
      candidate?.let { incidents[occurrenceToken] = it }
      candidate != null
    }

  override fun settleWithDiagnosis(
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    record: BlockIncidentDiagnosisRecord,
    nextEligibleAt: Instant?,
  ): Boolean =
    synchronized(this) {
      val occurrenceToken = record.occurrenceToken
      val candidate =
        settledCandidate(occurrenceToken, ownerToken, ownerGeneration, diagnosis, nextEligibleAt) ?: return false
      val stamped = record.copy(revision = candidate.state.revision)
      require(stamped.validation().isEmpty())
      incidents[occurrenceToken] = candidate
      diagnosisRecords.getOrPut(occurrenceToken) { mutableListOf() } += stamped
      true
    }

  override fun diagnoses(occurrenceToken: String): List<BlockIncidentDiagnosisRecord> =
    synchronized(this) {
      diagnosisRecords[occurrenceToken].orEmpty().sortedBy { it.revision }
    }

  private fun settledCandidate(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): BlockIncident? {
    val current = incidents[occurrenceToken] ?: return null
    return when (
      val decision =
        BlockIncidentTransitions.settle(
          current,
          clock.instant(),
          BlockIncidentOwner(ownerToken, ownerGeneration),
          diagnosis,
          nextEligibleAt,
        )
    ) {
      is BlockIncidentTransitionDecision.Accepted -> decision.candidate
      is BlockIncidentTransitionDecision.Refused -> null
    }
  }

  override fun expireDeadline(
    occurrenceToken: String,
    expectedRevision: Long,
    observedOwnerToken: String?,
    observedOwnerGeneration: Long?,
  ): Boolean =
    synchronized(this) {
      val current = incidents[occurrenceToken] ?: return false
      val candidate =
        when (
          val decision =
            BlockIncidentTransitions.expireDeadline(
              current,
              clock.instant(),
              expectedRevision,
              observedOwnerToken,
              observedOwnerGeneration,
            )
        ) {
          is BlockIncidentTransitionDecision.Accepted -> decision.candidate
          is BlockIncidentTransitionDecision.Refused -> return false
        }
      incidents[occurrenceToken] = candidate
      true
    }

  private companion object {
    const val OBSERVATION_SKEW_SECONDS = 300L
  }
}
