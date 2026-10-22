package skillbill.ports.blockincident

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import java.time.Instant

class InMemoryBlockIncidentRecoveryRepository : BlockIncidentRecoveryRepository {
  private val attempts = linkedMapOf<String, BlockIncidentRecoveryAttempt>()
  private val idsByKey = linkedMapOf<String, String>()

  @Synchronized
  override fun insertIfAbsent(attempt: BlockIncidentRecoveryAttempt): BlockIncidentRecoveryAttempt {
    idsByKey[attempt.idempotencyKey]?.let { return attempts.getValue(it) }
    attempts[attempt.attemptId] = attempt
    idsByKey[attempt.idempotencyKey] = attempt.attemptId
    return attempt
  }

  @Synchronized
  override fun transition(
    attemptId: String,
    expectedStatus: BlockIncidentRecoveryAttemptStatus,
    newStatus: BlockIncidentRecoveryAttemptStatus,
    failure: BlockIncidentRecoveryFailure?,
    ownerToken: String?,
    ownerGeneration: Long?,
    ownerExpiresAt: Instant?,
    now: Instant,
  ): Boolean {
    val current = attempts[attemptId] ?: return false
    if (current.status != expectedStatus || !expectedStatus.canMoveTo(newStatus)) return false
    if (current.status in BlockIncidentRecoveryAttemptStatus.OWNED && current.ownerToken != ownerToken) return false
    attempts[attemptId] =
      current.copy(
        status = newStatus,
        failure = failure,
        ownerToken = ownerToken.takeIf { ownerExpiresAt != null },
        ownerGeneration = ownerGeneration.takeIf { ownerExpiresAt != null },
        ownerExpiresAt = ownerExpiresAt,
        updatedAt = now,
      )
    return true
  }

  @Synchronized
  override fun inProgress(token: String): BlockIncidentRecoveryAttempt? =
    attempts.values.filter { it.occurrenceToken == token && it.status in BlockIncidentRecoveryAttemptStatus.IN_PROGRESS }
      .maxByOrNull { it.requestedAt }

  @Synchronized
  override fun byIdempotencyKey(key: String): BlockIncidentRecoveryAttempt? =
    attempts.values.firstOrNull { it.idempotencyKey == key }

  @Synchronized
  override fun latest(token: String): BlockIncidentRecoveryAttempt? =
    attempts.values.filter { it.occurrenceToken == token }.maxByOrNull { it.requestedAt }

  @Synchronized
  override fun history(token: String): List<BlockIncidentRecoveryAttempt> =
    attempts.values.filter { it.occurrenceToken == token }.sortedByDescending { it.requestedAt }.take(HISTORY_LIMIT)

  @Synchronized
  override fun interrupted(now: Instant, limit: Int): List<BlockIncidentRecoveryAttempt> {
    require(limit in 1..MAX_INTERRUPTED)
    return attempts.values.filter {
      it.status in BlockIncidentRecoveryAttemptStatus.OWNED && it.ownerExpiresAt?.isBefore(now) == true
    }.sortedBy { it.updatedAt }.take(limit)
  }

  private companion object {
    const val HISTORY_LIMIT = 64
    const val MAX_INTERRUPTED = 100
  }
}
