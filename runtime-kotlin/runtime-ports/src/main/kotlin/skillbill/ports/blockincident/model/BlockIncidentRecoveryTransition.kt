package skillbill.ports.blockincident.model

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import java.time.Instant

data class BlockIncidentRecoveryTransition(
  val attemptId: String,
  val expectedStatus: BlockIncidentRecoveryAttemptStatus,
  val newStatus: BlockIncidentRecoveryAttemptStatus,
  val failure: BlockIncidentRecoveryFailure?,
  val ownerToken: String?,
  val ownerGeneration: Long?,
  val ownerExpiresAt: Instant?,
  val now: Instant,
)
