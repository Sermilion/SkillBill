package skillbill.ports.approval

import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.ports.approval.model.ApprovalDeliveryState
import java.time.Instant

/**
 * The process-local capability of one live owning process generation. Only the broker holds it, only while the
 * owner is attached, and it is never persisted or inherited by a later generation.
 */
interface ApprovalOwnerEffects {
  /** Writes the correlated response for a durably claimed settlement to this exact process. */
  fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState

  /** Stops this owning process because continuing without a safe approval channel is not allowed. */
  fun stop(reason: String)
}

/** Fires a task at an absolute deadline without blocking the caller. */
interface ApprovalDeadlineScheduler {
  fun scheduleAt(
    deadline: Instant,
    task: Runnable,
  ): ApprovalScheduledDeadline
}

fun interface ApprovalScheduledDeadline {
  fun cancel()
}
