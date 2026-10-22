package skillbill.ports.blockincident

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import java.time.Instant

/** Stores fenced recovery attempts and their durable progress. */
interface BlockIncidentRecoveryRepository {
  /** Inserts an attempt or returns the existing attempt with the same idempotency key. */
  fun insertIfAbsent(attempt: BlockIncidentRecoveryAttempt): BlockIncidentRecoveryAttempt

  /**
   * Changes an attempt only when its current status and owner match the supplied values.
   * A null [ownerExpiresAt] clears all stored ownership fields after checking the supplied owner.
   */
  fun transition(
    attemptId: String,
    expectedStatus: BlockIncidentRecoveryAttemptStatus,
    newStatus: BlockIncidentRecoveryAttemptStatus,
    failure: BlockIncidentRecoveryFailure?,
    ownerToken: String?,
    ownerGeneration: Long?,
    ownerExpiresAt: Instant?,
    now: Instant,
  ): Boolean

  /** Returns the active attempt for this occurrence, if one exists. */
  fun inProgress(token: String): BlockIncidentRecoveryAttempt?

  /** Returns the attempt with this exact durable request key, regardless of its age. */
  fun byIdempotencyKey(key: String): BlockIncidentRecoveryAttempt?

  /** Returns the newest attempt for this occurrence. */
  fun latest(token: String): BlockIncidentRecoveryAttempt?

  /** Returns bounded attempt history, newest first. */
  fun history(token: String): List<BlockIncidentRecoveryAttempt>

  /** Returns expired applying or resuming attempts, oldest update first. */
  fun interrupted(now: Instant, limit: Int): List<BlockIncidentRecoveryAttempt>
}
