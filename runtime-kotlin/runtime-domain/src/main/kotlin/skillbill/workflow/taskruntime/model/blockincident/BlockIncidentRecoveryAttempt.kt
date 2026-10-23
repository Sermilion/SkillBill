package skillbill.workflow.taskruntime.model.blockincident

import skillbill.text.sha256HexUtf8
import java.time.Instant

data class BlockIncidentRecoveryAttempt(
  val attemptId: String,
  val occurrenceToken: String,
  val action: BlockIncidentRecoveryAction,
  val incidentRevision: Long,
  val idempotencyKey: String,
  val status: BlockIncidentRecoveryAttemptStatus,
  val failure: BlockIncidentRecoveryFailure?,
  val ownerToken: String?,
  val ownerGeneration: Long?,
  val ownerExpiresAt: Instant?,
  val requestedAt: Instant,
  val updatedAt: Instant,
  val targetWorkflowId: String,
  val targetInvocationId: String?,
) {
  companion object {
    fun idempotencyKey(
      token: String,
      action: BlockIncidentRecoveryAction,
      revision: Long,
    ): String = sha256HexUtf8("$token:${action.wireValue}:$revision")
  }
}
