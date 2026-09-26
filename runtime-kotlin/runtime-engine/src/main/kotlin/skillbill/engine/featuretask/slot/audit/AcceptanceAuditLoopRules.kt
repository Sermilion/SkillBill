package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.slot.PhaseForwardCheckpoint
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.workflow.taskruntime.model.core.PhaseSlot

internal object AcceptanceAuditLoopRules : PhaseLoopRules {
  private val auditedImplementation =
    PhaseForwardCheckpoint(
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_AUDITED_IMPLEMENTATION,
      blockedReason = ::auditReviewCheckpointBlockedReason,
    )

  override fun forwardCheckpoint(
    stepId: String,
    destinationStepId: String,
  ): PhaseForwardCheckpoint? =
    auditedImplementation.takeIf { PhaseSlot.slotForStep(destinationStepId) == PhaseSlot.CODE_REVIEW }
}

internal fun auditReviewCheckpointBlockedReason(
  branch: String,
  error: String,
): String =
  "Feature-task-runtime could not commit the audited implementation on the feature branch '$branch' " +
    "before review" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to review an uncommitted final audit iteration."
