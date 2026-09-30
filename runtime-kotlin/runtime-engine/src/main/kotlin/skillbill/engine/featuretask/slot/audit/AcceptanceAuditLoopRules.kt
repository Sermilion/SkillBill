package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.checkpoint.auditReviewCheckpointBlockedReason
import skillbill.engine.featuretask.slot.PhaseForwardCheckpoint
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object AcceptanceAuditLoopRules : PhaseLoopRules {
  override fun resumesInFlightReentry(loopId: String): Boolean =
    loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID

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
