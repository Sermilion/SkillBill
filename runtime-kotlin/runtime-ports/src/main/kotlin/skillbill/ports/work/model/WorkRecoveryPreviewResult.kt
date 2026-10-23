package skillbill.ports.work.model

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPrerequisiteInstruction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview

sealed interface WorkRecoveryPreviewResult {
  /** Recovery is admissible now; [preview] names the action, its effects, and the exact target. */
  data class Available(val preview: BlockIncidentRecoveryPreview) : WorkRecoveryPreviewResult

  /**
   * Recovery waits on [instructions]. [plannedPreview] is what recovery would do once every user-confirmable
   * prerequisite is confirmed, null when confirmation alone cannot make it admissible.
   */
  data class NeedsPrerequisites(
    val instructions: List<BlockIncidentRecoveryPrerequisiteInstruction>,
    val plannedPreview: BlockIncidentRecoveryPreview?,
  ) : WorkRecoveryPreviewResult

  /** An attempt with the same idempotency key already exists, so a recover request replays it. */
  data class ExistingAttempt(
    val attemptId: String,
    val status: BlockIncidentRecoveryAttemptStatus,
    val failure: BlockIncidentRecoveryFailure?,
  ) : WorkRecoveryPreviewResult

  data class Rejected(val reason: String, val detail: String? = null) : WorkRecoveryPreviewResult
}
