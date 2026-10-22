package skillbill.ports.blockincident

import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentState
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitionDecision
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitions
import java.time.Clock
import java.time.Instant

class InMemoryBlockIncidentRepository(private val clock: Clock = Clock.systemUTC()) : BlockIncidentRepository {
  private val incidents = linkedMapOf<String, BlockIncident>()

  private val aliases = linkedMapOf<Pair<String, String>, String>()
  private val blockedWorkflows = linkedMapOf<String, BlockedWorkflowWithoutIncident>()

  override fun findByAlias(alias: BlockIncidentAlias): BlockIncident? = synchronized(this) {
    aliases[alias.kind.wireValue to alias.value]?.let(incidents::get)
  }

  override fun latestForWorkflow(workflowId: String): BlockIncident? = synchronized(this) {
    incidents.values.filter { it.occurrence.workflowId == workflowId }
      .maxByOrNull { it.occurrence.observedAt }
  }


  override fun blockedWithoutIncident(since: Instant, limit: Int): List<BlockedWorkflowWithoutIncident> = synchronized(this) {
    blockedWorkflows.values.filter { it.updatedAt >= since && latestForWorkflow(it.workflowId) == null }
      .sortedBy { it.updatedAt }.take(limit)
  }

  fun recordBlockedWorkflow(workflow: BlockedWorkflowWithoutIncident) = synchronized(this) {
    blockedWorkflows[workflow.workflowId] = workflow
  }

  override fun createOrLink(
    occurrence: BlockIncidentOccurrence,
    alias: BlockIncidentAlias,
    deadline: Instant,
  ): BlockIncident = synchronized(this) {
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
          skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery.UNAVAILABLE,
          skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReport.UNAVAILABLE,
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

  override fun pending(limit: Int): List<BlockIncident> = synchronized(this) {
    incidents.values.filter { it.state.diagnosis == BlockIncidentDiagnosis.PENDING }.take(limit)
  }

  override fun claim(
    occurrenceToken: String,
    expectedRevision: Long,
    ownerToken: String,
    ownerGeneration: Long,
    hostIdentity: String,
    processId: Long,
    expiresAt: Instant,
  ): Boolean = synchronized(this) {
    val current = incidents[occurrenceToken] ?: return false
    val candidate = when (val decision = BlockIncidentTransitions.claim(
      current, clock.instant(), expectedRevision,
      skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim(
        ownerToken, ownerGeneration, hostIdentity, processId, expiresAt,
      ),
    )) {
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
  ): Boolean = synchronized(this) {
    val current = incidents[occurrenceToken] ?: return false
    val candidate = when (val decision = BlockIncidentTransitions.renew(
      current, clock.instant(), ownerToken, ownerGeneration, expiresAt,
    )) {
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
  ): Boolean = synchronized(this) {
    val current = incidents[occurrenceToken] ?: return false
    val candidate = when (val decision = BlockIncidentTransitions.settle(
      current, clock.instant(), ownerToken, ownerGeneration, diagnosis, nextEligibleAt,
    )) {
      is BlockIncidentTransitionDecision.Accepted -> decision.candidate
      is BlockIncidentTransitionDecision.Refused -> return false
    }
    incidents[occurrenceToken] = candidate
    true
  }

  override fun expireDeadline(
    occurrenceToken: String,
    expectedRevision: Long,
    observedOwnerToken: String?,
    observedOwnerGeneration: Long?,
  ): Boolean = synchronized(this) {
    val current = incidents[occurrenceToken] ?: return false
    val candidate = when (
      val decision = BlockIncidentTransitions.expireDeadline(
        current, clock.instant(), expectedRevision, observedOwnerToken, observedOwnerGeneration,
      )
    ) {
      is BlockIncidentTransitionDecision.Accepted -> decision.candidate
      is BlockIncidentTransitionDecision.Refused -> return false
    }
    incidents[occurrenceToken] = candidate
    true
  }
}
