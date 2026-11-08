package skillbill.application.approval

import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.approval.ApprovalDeadlineScheduler
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.ApprovalScheduledDeadline
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerLease
import skillbill.ports.approval.model.ApprovalRequestIdentity
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal val APPROVAL_OWNER_LEASE: Duration = Duration.ofMinutes(1)

internal fun ApprovalOwnerLease.isLive(now: Instant): Boolean =
  endedAt == null && expiresAt?.let(::parseInstant)?.isAfter(now) == true

internal class ApprovalOwnerDelivery(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val scheduler: ApprovalDeadlineScheduler,
  private val onClaimSeen: (ApprovalRequestIdentity) -> Unit,
  private val onUncertain: (ApprovalOwnerKey) -> Unit,
) {
  private class Attachment(val nonce: String, val effects: ApprovalOwnerEffects) {
    @Volatile
    var tick: ApprovalScheduledDeadline? = null
  }

  private val attachments = HashMap<ApprovalOwnerKey, Attachment>()

  fun attach(
    key: ApprovalOwnerKey,
    nonce: String,
    effects: ApprovalOwnerEffects,
  ) {
    val attachment = Attachment(nonce, effects)
    synchronized(attachments) { attachments[key] = attachment }
    scheduleTick(key, attachment)
  }

  fun localNonce(key: ApprovalOwnerKey): String? = current(key)?.nonce

  fun detach(key: ApprovalOwnerKey): ApprovalOwnerEffects? {
    val attachment = synchronized(attachments) { attachments.remove(key) } ?: return null
    attachment.tick?.cancel()
    return attachment.effects
  }

  fun drain(
    key: ApprovalOwnerKey,
    grantsAllowed: Boolean,
  ): Boolean {
    val attachment = current(key) ?: return false
    return synchronized(attachment) {
      val owned =
        current(key) === attachment &&
          database.read { unit -> unit.approvals.owner(key) }?.nonce == attachment.nonce
      owned &&
        database.read { unit -> unit.approvals.claimedDeliveries(key) }.any { stored ->
          deliverClaim(attachment, stored.record, grantsAllowed) == ApprovalDeliveryState.UNCERTAIN
        }
    }
  }

  private fun deliverClaim(
    attachment: Attachment,
    record: ApprovalRequestRecord,
    grantsAllowed: Boolean,
  ): ApprovalDeliveryState? {
    val identity = record.identity()
    onClaimSeen(identity)
    if (record.state == ApprovalState.ALLOWED && !grantsAllowed) return null
    val outcome =
      catchingNonCancellation { attachment.effects.deliver(record) }
        .onFailure { failure -> diagnostics.error("Approval response delivery failed.", failure) }
        .getOrDefault(ApprovalDeliveryState.UNCERTAIN)
    val recorded =
      catchingNonCancellation { database.transaction { unit -> unit.approvals.finishDelivery(identity, outcome) } }
        .onFailure { failure -> diagnostics.error("Approval delivery state could not be recorded.", failure) }
        .getOrDefault(false)
    return if (recorded) outcome else ApprovalDeliveryState.UNCERTAIN
  }

  private fun current(key: ApprovalOwnerKey): Attachment? = synchronized(attachments) { attachments[key] }

  private fun scheduleTick(
    key: ApprovalOwnerKey,
    attachment: Attachment,
  ) {
    attachment.tick = scheduler.scheduleAt(clock.instant().plus(OWNER_TICK)) { tick(key, attachment) }
  }

  private fun tick(
    key: ApprovalOwnerKey,
    attachment: Attachment,
  ) {
    if (current(key) !== attachment) return
    val keepTicking =
      catchingNonCancellation { renewAndDrain(key, attachment) }
        .onFailure { failure -> diagnostics.error("Approval owner tick failed.", failure) }
        .getOrDefault(true)
    if (keepTicking && current(key) === attachment) scheduleTick(key, attachment)
  }

  private fun renewAndDrain(
    key: ApprovalOwnerKey,
    attachment: Attachment,
  ): Boolean {
    if (!renew(key, attachment)) return false
    if (drain(key, grantsAllowed = true)) onUncertain(key)
    return true
  }

  private fun renew(
    key: ApprovalOwnerKey,
    attachment: Attachment,
  ): Boolean {
    val now = clock.instant()
    return database.transaction { unit ->
      val lease = unit.approvals.owner(key)
      if (lease == null || lease.endedAt != null || lease.nonce != attachment.nonce) {
        false
      } else {
        val remaining = lease.expiresAt?.let(::parseInstant)?.let { Duration.between(now, it) } ?: Duration.ZERO
        if (remaining < APPROVAL_OWNER_LEASE.dividedBy(2)) {
          unit.approvals.saveOwner(lease.copy(expiresAt = now.plus(APPROVAL_OWNER_LEASE).toString()))
        }
        true
      }
    }
  }

  private companion object {
    val OWNER_TICK: Duration = Duration.ofSeconds(1)
  }
}
