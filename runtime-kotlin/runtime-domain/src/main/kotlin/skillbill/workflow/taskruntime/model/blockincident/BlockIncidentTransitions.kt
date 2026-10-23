package skillbill.workflow.taskruntime.model.blockincident

import java.time.Duration
import java.time.Instant

sealed interface BlockIncidentTransitionDecision {
  data class Accepted(val candidate: BlockIncident) : BlockIncidentTransitionDecision

  data class Refused(val reason: BlockIncidentTransitionRefusal) : BlockIncidentTransitionDecision
}

enum class BlockIncidentTransitionRefusal {
  INVALID_CURRENT,
  STALE_REVISION,
  NOT_PENDING,
  RETRIES_EXHAUSTED,
  NOT_ELIGIBLE,
  DEADLINE_EXPIRED,
  CLAIM_MISMATCH,
  CLAIM_EXPIRED,
  INVALID_LEASE,
  INVALID_SETTLEMENT,
}

data class BlockIncidentOwner(
  val ownerToken: String,
  val ownerGeneration: Long,
)

object BlockIncidentTransitions {
  const val MAX_RETRIES = 2
  private const val INVESTIGATION_DEADLINE_MINUTES = 5L
  private val INVESTIGATION_DEADLINE: Duration = Duration.ofMinutes(INVESTIGATION_DEADLINE_MINUTES)

  fun claim(
    current: BlockIncident,
    now: Instant,
    expectedRevision: Long,
    claim: BlockIncidentClaim,
  ): BlockIncidentTransitionDecision {
    val takeover =
      current.state.diagnosis == BlockIncidentDiagnosis.INVESTIGATING &&
        current.state.claim?.expiresAt?.let { it <= now } == true
    val deadline = now.plus(INVESTIGATION_DEADLINE)
    val refusal =
      claimRefusal(current, now, expectedRevision, takeover)
        ?: BlockIncidentTransitionRefusal.INVALID_LEASE.takeUnless { claim.isValidLease(now, deadline) }
    if (refusal != null) return BlockIncidentTransitionDecision.Refused(refusal)
    val takeoverRetry = if (takeover && current.state.retryCount < MAX_RETRIES) 1 else 0
    return current.copy(
      state =
        current.state.copy(
          diagnosis = BlockIncidentDiagnosis.INVESTIGATING,
          nextEligibleAt = null,
          revision = current.state.revision + 1,
          claim = claim,
          deadline = deadline,
          retryCount = current.state.retryCount + takeoverRetry,
        ),
    ).acceptedOrRefused()
  }

  fun renew(
    current: BlockIncident,
    now: Instant,
    owner: BlockIncidentOwner,
    expiresAt: Instant,
  ): BlockIncidentTransitionDecision {
    val claim = current.state.claim
    val refusal =
      ownedClaimRefusal(current, now, owner)
        ?: BlockIncidentTransitionRefusal.INVALID_LEASE.takeIf {
          claim == null || expiresAt <= claim.expiresAt || expiresAt > current.state.deadline
        }
    if (refusal != null || claim == null) {
      return BlockIncidentTransitionDecision.Refused(refusal ?: BlockIncidentTransitionRefusal.CLAIM_MISMATCH)
    }
    return current.copy(
      state =
        current.state.copy(
          revision = current.state.revision + 1,
          claim = claim.copy(expiresAt = expiresAt),
        ),
    ).acceptedOrRefused()
  }

  fun settle(
    current: BlockIncident,
    now: Instant,
    owner: BlockIncidentOwner,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): BlockIncidentTransitionDecision {
    val refusal =
      ownedClaimRefusal(current, now, owner)
        ?: BlockIncidentTransitionRefusal.INVALID_SETTLEMENT.takeUnless {
          current.state.acceptsSettlement(now, diagnosis, nextEligibleAt)
        }
    if (refusal != null) return BlockIncidentTransitionDecision.Refused(refusal)
    val exhaustedCrash = diagnosis == BlockIncidentDiagnosis.CRASHED && current.state.retryCount >= MAX_RETRIES
    return current.copy(
      state =
        current.state.copy(
          diagnosis = diagnosis,
          retryCount = current.state.retryCount + if (exhaustedCrash) 0 else 1,
          revision = current.state.revision + 1,
          nextEligibleAt = nextEligibleAt,
          claim = null,
        ),
    ).acceptedOrRefused()
  }

  fun expireDeadline(
    current: BlockIncident,
    now: Instant,
    expectedRevision: Long,
    observedOwnerToken: String?,
    observedOwnerGeneration: Long?,
  ): BlockIncidentTransitionDecision {
    val claim = current.state.claim
    val ownerMatches = claim?.ownerToken == observedOwnerToken && claim?.ownerGeneration == observedOwnerGeneration
    val refusal =
      when {
        !current.isValid() -> BlockIncidentTransitionRefusal.INVALID_CURRENT
        current.state.revision != expectedRevision -> BlockIncidentTransitionRefusal.STALE_REVISION
        current.state.deadline > now -> BlockIncidentTransitionRefusal.NOT_ELIGIBLE
        current.state.diagnosis !in EXPIRABLE_DIAGNOSES -> BlockIncidentTransitionRefusal.NOT_PENDING
        !ownerMatches -> BlockIncidentTransitionRefusal.CLAIM_MISMATCH
        else -> null
      }
    if (refusal != null) return BlockIncidentTransitionDecision.Refused(refusal)
    return current.copy(
      state =
        current.state.copy(
          diagnosis = BlockIncidentDiagnosis.TIMED_OUT,
          revision = current.state.revision + 1,
          retryCount = current.state.retryCount + if (claim == null) 0 else 1,
          nextEligibleAt = null,
          claim = null,
        ),
    ).acceptedOrRefused()
  }

  private fun claimRefusal(
    current: BlockIncident,
    now: Instant,
    expectedRevision: Long,
    takeover: Boolean,
  ): BlockIncidentTransitionRefusal? =
    when {
      !current.isValid() -> BlockIncidentTransitionRefusal.INVALID_CURRENT
      current.state.revision != expectedRevision -> BlockIncidentTransitionRefusal.STALE_REVISION
      current.state.diagnosis != BlockIncidentDiagnosis.PENDING && !takeover ->
        BlockIncidentTransitionRefusal.NOT_PENDING
      current.state.retryCount >= MAX_RETRIES && !takeover -> BlockIncidentTransitionRefusal.RETRIES_EXHAUSTED
      current.state.deadline <= now -> BlockIncidentTransitionRefusal.DEADLINE_EXPIRED
      current.state.nextEligibleAt?.isAfter(now) == true -> BlockIncidentTransitionRefusal.NOT_ELIGIBLE
      else -> null
    }

  private fun ownedClaimRefusal(
    current: BlockIncident,
    now: Instant,
    owner: BlockIncidentOwner,
  ): BlockIncidentTransitionRefusal? {
    val claim = current.state.claim
    val owned =
      current.state.diagnosis == BlockIncidentDiagnosis.INVESTIGATING &&
        claim?.ownerToken == owner.ownerToken &&
        claim.ownerGeneration == owner.ownerGeneration
    return when {
      !current.isValid() -> BlockIncidentTransitionRefusal.INVALID_CURRENT
      !owned -> BlockIncidentTransitionRefusal.CLAIM_MISMATCH
      claim.expiresAt <= now -> BlockIncidentTransitionRefusal.CLAIM_EXPIRED
      else -> null
    }
  }

  private fun BlockIncidentClaim.isValidLease(
    now: Instant,
    deadline: Instant,
  ): Boolean {
    val window = expiresAt.isAfter(now) && !expiresAt.isAfter(deadline)
    val holder = ownerToken.isNotBlank() && ownerGeneration > 0
    val process = hostIdentity.isNotBlank() && processId > 0
    return window && holder && process
  }

  private fun BlockIncidentState.acceptsSettlement(
    now: Instant,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): Boolean {
    val timeoutMatchesDeadline = (diagnosis == BlockIncidentDiagnosis.TIMED_OUT) == (deadline <= now)
    val eligibility =
      when (diagnosis) {
        BlockIncidentDiagnosis.INVESTIGATING -> false
        BlockIncidentDiagnosis.PENDING ->
          retryCount + 1 < MAX_RETRIES &&
            nextEligibleAt != null &&
            nextEligibleAt > now &&
            nextEligibleAt < deadline
        else -> nextEligibleAt == null
      }
    return timeoutMatchesDeadline && eligibility
  }

  private fun BlockIncident.acceptedOrRefused(): BlockIncidentTransitionDecision =
    if (isValid()) {
      BlockIncidentTransitionDecision.Accepted(this)
    } else {
      BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.INVALID_SETTLEMENT)
    }

  private val EXPIRABLE_DIAGNOSES = setOf(BlockIncidentDiagnosis.PENDING, BlockIncidentDiagnosis.INVESTIGATING)
}
