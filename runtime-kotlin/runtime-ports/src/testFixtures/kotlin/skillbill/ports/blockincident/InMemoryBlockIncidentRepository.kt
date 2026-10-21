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

  override fun createOrLink(
    occurrence: BlockIncidentOccurrence,
    alias: BlockIncidentAlias,
    deadline: Instant,
  ): BlockIncident = synchronized(this) {
    aliases[alias.kind.wireValue to alias.value]?.let(incidents::get)?.let { return it }
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
  ): Boolean = false

  override fun renew(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    expiresAt: Instant,
  ): Boolean = false

  override fun settle(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): Boolean = false

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
