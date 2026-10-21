package skillbill.workflow.taskruntime.model.blockincident

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

object BlockIncidentTransitions {
  private const val MAX_RETRIES = 2

  fun claim(
    current: BlockIncident,
    now: Instant,
    expectedRevision: Long,
    claim: BlockIncidentClaim,
  ): BlockIncidentTransitionDecision {
    if (!current.isValid()) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.INVALID_CURRENT,
    )
    if (current.state.revision != expectedRevision) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.STALE_REVISION,
    )
    if (current.state.diagnosis != BlockIncidentDiagnosis.PENDING) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.NOT_PENDING,
    )
    if (current.state.retryCount >= MAX_RETRIES) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.RETRIES_EXHAUSTED,
    )
    if (current.state.deadline <= now) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.DEADLINE_EXPIRED,
    )
    if (current.state.nextEligibleAt?.isAfter(now) == true) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.NOT_ELIGIBLE,
    )
    if (!claim.expiresAt.isAfter(now) || claim.expiresAt.isAfter(current.state.deadline) ||
      claim.ownerToken.isBlank() || claim.ownerGeneration <= 0 || claim.hostIdentity.isBlank() || claim.processId <= 0
    ) return BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.INVALID_LEASE)
    val candidate = current.copy(
      state = current.state.copy(
        diagnosis = BlockIncidentDiagnosis.INVESTIGATING,
        nextEligibleAt = null,
        revision = current.state.revision + 1,
        claim = claim,
      ),
    )
    return candidate.acceptedOrRefused()
  }

  fun renew(
    current: BlockIncident,
    now: Instant,
    ownerToken: String,
    ownerGeneration: Long,
    expiresAt: Instant,
  ): BlockIncidentTransitionDecision {
    if (!current.isValid()) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.INVALID_CURRENT,
    )
    val claim = current.state.claim
    if (current.state.diagnosis != BlockIncidentDiagnosis.INVESTIGATING || claim == null ||
      claim.ownerToken != ownerToken || claim.ownerGeneration != ownerGeneration
    ) return BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.CLAIM_MISMATCH)
    if (claim.expiresAt <= now) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.CLAIM_EXPIRED,
    )
    if (expiresAt <= claim.expiresAt || expiresAt > current.state.deadline) {
      return BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.INVALID_LEASE)
    }
    val candidate = current.copy(
      state = current.state.copy(
        revision = current.state.revision + 1,
        claim = claim.copy(expiresAt = expiresAt),
      ),
    )
    return candidate.acceptedOrRefused()
  }

  fun settle(
    current: BlockIncident,
    now: Instant,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): BlockIncidentTransitionDecision {
    if (!current.isValid()) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.INVALID_CURRENT,
    )
    val claim = current.state.claim
    if (current.state.diagnosis != BlockIncidentDiagnosis.INVESTIGATING || claim == null ||
      claim.ownerToken != ownerToken || claim.ownerGeneration != ownerGeneration
    ) return BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.CLAIM_MISMATCH)
    if (claim.expiresAt <= now) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.CLAIM_EXPIRED,
    )
    if (diagnosis == BlockIncidentDiagnosis.INVESTIGATING || diagnosis == BlockIncidentDiagnosis.PENDING &&
      (current.state.retryCount + 1 >= MAX_RETRIES || nextEligibleAt == null || nextEligibleAt <= now ||
        nextEligibleAt >= current.state.deadline) ||
      (diagnosis != BlockIncidentDiagnosis.PENDING && nextEligibleAt != null) ||
      (diagnosis == BlockIncidentDiagnosis.TIMED_OUT) != (current.state.deadline <= now)
    ) return BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.INVALID_SETTLEMENT)
    val candidate = current.copy(
      state = current.state.copy(
        diagnosis = diagnosis,
        retryCount = current.state.retryCount + 1,
        revision = current.state.revision + 1,
        nextEligibleAt = nextEligibleAt,
        claim = null,
      ),
    )
    return candidate.acceptedOrRefused()
  }

  fun expireDeadline(
    current: BlockIncident,
    now: Instant,
    expectedRevision: Long,
    observedOwnerToken: String?,
    observedOwnerGeneration: Long?,
  ): BlockIncidentTransitionDecision {
    if (!current.isValid()) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.INVALID_CURRENT,
    )
    if (current.state.revision != expectedRevision) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.STALE_REVISION,
    )
    if (current.state.deadline > now) return BlockIncidentTransitionDecision.Refused(
      BlockIncidentTransitionRefusal.NOT_ELIGIBLE,
    )
    val claim = current.state.claim
    if (current.state.diagnosis != BlockIncidentDiagnosis.PENDING &&
      current.state.diagnosis != BlockIncidentDiagnosis.INVESTIGATING
    ) return BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.NOT_PENDING)
    if (claim?.ownerToken != observedOwnerToken || claim?.ownerGeneration != observedOwnerGeneration) {
      return BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.CLAIM_MISMATCH)
    }
    val candidate = current.copy(
      state = current.state.copy(
        diagnosis = BlockIncidentDiagnosis.TIMED_OUT,
        revision = current.state.revision + 1,
        retryCount = current.state.retryCount + if (claim == null) 0 else 1,
        nextEligibleAt = null,
        claim = null,
      ),
    )
    return candidate.acceptedOrRefused()
  }

  private fun BlockIncident.acceptedOrRefused(): BlockIncidentTransitionDecision =
    if (isValid()) BlockIncidentTransitionDecision.Accepted(this)
    else BlockIncidentTransitionDecision.Refused(BlockIncidentTransitionRefusal.INVALID_SETTLEMENT)
}
