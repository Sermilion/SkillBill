package skillbill.application.approval

import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.ApprovalTerminalCause
import skillbill.approval.model.settleFor
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.UnitOfWork
import skillbill.ports.approval.ApprovalDeadlineScheduler
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.ApprovalScheduledDeadline
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerLease
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRequestIdentity
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Instant

internal class ApprovalLifecycle(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val scheduler: ApprovalDeadlineScheduler,
) {
  private val deadlines = HashMap<ApprovalRequestIdentity, ApprovalScheduledDeadline>()
  private val delivery =
    ApprovalOwnerDelivery(
      database,
      clock,
      diagnostics,
      scheduler,
      onClaimSeen = { identity -> cancelDeadline(identity) },
      onUncertain = { key -> endOwner(key, ApprovalTerminalCause.PROCESS_END, false, UNCERTAIN_WRITE_REASON) },
    )

  fun attach(
    key: ApprovalOwnerKey,
    nonce: String,
    effects: ApprovalOwnerEffects,
  ) {
    delivery.attach(key, nonce, effects)
  }

  fun durableOwner(
    unit: UnitOfWork,
    key: ApprovalOwnerKey,
    now: Instant,
  ): ApprovalOwnerLease? = unit.approvals.owner(key)?.takeIf { lease -> lease.isLive(now) }

  fun localOwner(
    unit: UnitOfWork,
    key: ApprovalOwnerKey,
    now: Instant,
  ): ApprovalOwnerLease? = durableOwner(unit, key, now)?.takeIf { lease -> lease.nonce == delivery.localNonce(key) }

  fun schedule(
    identity: ApprovalRequestIdentity,
    deadline: Instant,
  ) {
    val scheduled = scheduler.scheduleAt(deadline) { expireWhenDue(identity, deadline) }
    synchronized(deadlines) { deadlines[identity] = scheduled }
  }

  fun settleTerminal(
    identity: ApprovalRequestIdentity,
    cause: ApprovalTerminalCause,
    deliver: Boolean,
  ): ApprovalDecisionResult {
    val now = clock.instant()
    val result: ApprovalDecisionResult =
      database.transaction { unit ->
        val stored = unit.approvals.find(identity)
        val existing = stored?.record
        val deadline = existing?.takeIf { it.state == ApprovalState.PENDING }?.let { parseInstant(it.deadlineAt) }
        when {
          existing == null -> ApprovalDecisionResult.Missing
          existing.state != ApprovalState.PENDING -> ApprovalDecisionResult.AlreadySettled(existing)
          deadline == null -> ApprovalDecisionResult.InvalidRecord(existing)
          else -> settleLocked(unit, existing, cause, deadline, now)
        }
      }
    if (result is ApprovalDecisionResult.Settled) afterSettled(result.request, deliver)
    return result
  }

  fun clearByProvider(
    identity: ApprovalRequestIdentity,
    clearance: ApprovalProviderClearance,
    nonce: String,
  ): ApprovalDecisionResult {
    val now = clock.instant()
    val result =
      database.transaction { unit ->
        val stored = unit.approvals.find(identity)
        val existing = stored?.record
        val owner = localOwner(unit, identity.ownerKey(), now)
        when {
          existing == null -> ApprovalDecisionResult.Missing
          owner?.nonce != nonce -> ApprovalDecisionResult.NotLive(existing)
          else -> clearOwnedRequest(unit, identity, existing, clearance, now)
        }
      }
    if (result is ApprovalDecisionResult.Settled || result is ApprovalDecisionResult.AlreadySettled) {
      cancelDeadline(identity)
    }
    return result
  }

  private fun clearOwnedRequest(
    unit: UnitOfWork,
    identity: ApprovalRequestIdentity,
    existing: ApprovalRequestRecord,
    clearance: ApprovalProviderClearance,
    now: Instant,
  ): ApprovalDecisionResult {
    if (clearance == ApprovalProviderClearance.DENIED) {
      unit.approvals.recordProviderDisposition(identity, ApprovalProviderDisposition.DENIED)
    }
    return when {
      existing.state != ApprovalState.PENDING -> {
        ApprovalDecisionResult.AlreadySettled(existing)
      }
      else -> {
        val deadline = parseInstant(existing.deadlineAt)
        if (deadline == null) {
          ApprovalDecisionResult.InvalidRecord(existing)
        } else {
          val cause =
            when (clearance) {
              ApprovalProviderClearance.RESOLVED -> ApprovalTerminalCause.DISMISSAL
              ApprovalProviderClearance.DENIED -> ApprovalTerminalCause.PROVIDER_DENIAL
            }
          val terminal = existing.settleFor(cause, deadline, now)
          when {
            terminal == null -> ApprovalDecisionResult.DeadlineNotReached(existing)
            unit.approvals.settleWithoutDelivery(identity, terminal) -> ApprovalDecisionResult.Settled(terminal)
            else -> ApprovalDecisionResult.AlreadySettled(unit.approvals.find(identity)?.record ?: existing)
          }
        }
      }
    }
  }

  fun afterSettled(
    settled: ApprovalRequestRecord,
    deliver: Boolean,
  ) {
    cancelDeadline(settled.identity())
    val key = settled.ownerKey()
    if (deliver && delivery.drain(key, grantsAllowed = true)) {
      endOwner(key, ApprovalTerminalCause.PROCESS_END, deliverCancels = false, stopReason = UNCERTAIN_WRITE_REASON)
    }
  }

  fun endOwner(
    key: ApprovalOwnerKey,
    cause: ApprovalTerminalCause,
    deliverCancels: Boolean,
    stopReason: String?,
  ): Boolean {
    val endedAt = clock.instant().toString()
    val exists =
      database.transaction { unit ->
        val lease = unit.approvals.owner(key)
        if (lease != null && lease.endedAt == null) unit.approvals.saveOwner(lease.copy(endedAt = endedAt))
        lease != null
      }
    if (!exists) return false
    database.read { unit -> unit.approvals.pending(key.repositoryIdentity, key) }.forEach { stored ->
      settleTerminal(stored.record.identity(), cause, deliver = false)
    }
    val uncertain = deliverCancels && delivery.drain(key, grantsAllowed = false)
    val effects = delivery.detach(key)
    val owned = synchronized(deadlines) { deadlines.keys.filter { identity -> identity.ownerKey() == key } }
    owned.forEach(::cancelDeadline)
    val reason = stopReason ?: UNCERTAIN_WRITE_REASON.takeIf { uncertain }
    if (reason != null) effects?.stop(reason)
    return true
  }

  fun cancelRepository(repositoryIdentity: String) {
    database.read { unit -> unit.approvals.pending(repositoryIdentity, null) }.forEach { stored ->
      settleTerminal(stored.record.identity(), ApprovalTerminalCause.CONSUMER_CLOSED, deliver = true)
    }
  }

  private fun cancelDeadline(identity: ApprovalRequestIdentity) {
    synchronized(deadlines) { deadlines.remove(identity) }?.cancel()
  }

  private fun settleLocked(
    unit: UnitOfWork,
    existing: ApprovalRequestRecord,
    cause: ApprovalTerminalCause,
    deadline: Instant,
    now: Instant,
  ): ApprovalDecisionResult {
    val identity = existing.identity()
    val settled = existing.settleFor(cause, deadline, now)
    return when {
      settled == null -> ApprovalDecisionResult.DeadlineNotReached(existing)
      unit.approvals.settleAndClaim(identity, settled, null, now.toString()) -> ApprovalDecisionResult.Settled(settled)
      else -> ApprovalDecisionResult.AlreadySettled(unit.approvals.find(identity)?.record ?: existing)
    }
  }

  private fun expireWhenDue(
    identity: ApprovalRequestIdentity,
    deadline: Instant,
  ) {
    catchingNonCancellation {
      val result = settleTerminal(identity, ApprovalTerminalCause.EXPIRY, deliver = true)
      if (result is ApprovalDecisionResult.DeadlineNotReached) {
        schedule(identity, deadline)
      }
    }.onFailure { failure -> diagnostics.error("Approval expiry failed.", failure) }
  }

  private companion object {
    const val UNCERTAIN_WRITE_REASON =
      "The approval response write is uncertain, so the owning process cannot " +
        "continue safely."
  }
}
