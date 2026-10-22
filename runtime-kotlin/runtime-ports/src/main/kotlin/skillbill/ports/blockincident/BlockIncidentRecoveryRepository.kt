package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.BlockIncidentRecoveryTransition
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import java.time.Instant

/** Stores fenced recovery attempts and their durable progress. */
interface BlockIncidentRecoveryRepository {
  /** Inserts an attempt or returns the existing attempt with the same idempotency key. */
  fun insertIfAbsent(attempt: BlockIncidentRecoveryAttempt): BlockIncidentRecoveryAttempt

  /**
   * Changes an attempt only when its current status and owner match the supplied values.
   * A null [BlockIncidentRecoveryTransition.ownerExpiresAt] clears stored ownership
   * after checking the supplied owner.
   */
  fun transition(change: BlockIncidentRecoveryTransition): Boolean

  /** Returns the active attempt for this occurrence, if one exists. */
  fun inProgress(token: String): BlockIncidentRecoveryAttempt?

  /** Returns the attempt with this exact durable request key, regardless of its age. */
  fun byIdempotencyKey(key: String): BlockIncidentRecoveryAttempt?

  /** Returns the newest attempt for this occurrence. */
  fun latest(token: String): BlockIncidentRecoveryAttempt?

  /** Returns bounded attempt history, newest first. */
  fun history(token: String): List<BlockIncidentRecoveryAttempt>

  /** Returns expired applying or resuming attempts, oldest update first. */
  fun interrupted(
    now: Instant,
    limit: Int,
  ): List<BlockIncidentRecoveryAttempt>
}
