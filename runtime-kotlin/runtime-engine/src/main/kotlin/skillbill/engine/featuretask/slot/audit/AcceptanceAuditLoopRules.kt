package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.checkpoint.auditReviewCheckpointBlockedReason
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.PhaseEntrySettlement
import skillbill.engine.featuretask.slot.PhaseForwardCheckpoint
import skillbill.engine.featuretask.slot.PhaseLoopContext
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.audit.claim.NoChangeClaimResolution
import skillbill.engine.featuretask.slot.audit.claim.NoChangeOperatorRetry
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
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

  override fun settleWithoutLaunch(
    stepId: String,
    context: PhaseLoopContext,
    state: PhaseAcceptedStepExecution,
  ): PhaseEntrySettlement? {
    if (stepId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    val retry =
      NoChangeClaimResolution.pendingOperatorRetry(context.noChangePause(), context.phaseRecord(stepId)) ?: return null
    return runCatching { state.settleRuntimeAuthoredCompletion(stepId, retryEnvelope(retry)) }.fold(
      onSuccess = { PhaseEntrySettlement.Completed(FeatureTaskRuntimeVerdict.ADVANCE) },
      onFailure = { error ->
        PhaseEntrySettlement.Blocked(
          "Operator retry_fix could not settle the paused audit: ${error.message.orEmpty()}",
        )
      },
    )
  }

  private fun retryEnvelope(retry: NoChangeOperatorRetry): NormalizedFeatureTaskRuntimePhaseOutput {
    val criterionIds = retry.pause.criteria.joinToString { it.criterionId }
    val prose =
      "Operator instructions: ${retry.instructions}\n" +
        "The operator rejected the audit-confirmed no-change claim (${retry.pause.reason.wireValue}). " +
        "Every criterion the claim covered stays open: $criterionIds. " +
        "Suggested handoff: ${retry.pause.suggestedHandoff}"
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        linkedMapOf<String, Any?>(
          SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          SharedPayloadKeys.PHASE_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          SharedPayloadKeys.STATUS to STATUS_COMPLETED,
          SharedPayloadKeys.SUMMARY to "Operator rejected the confirmed no-change claim (retry_fix).",
          SharedPayloadKeys.VERDICT to FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED.wireValue,
          SharedPayloadKeys.PRODUCED_OUTPUTS to linkedMapOf<String, Any?>(SharedPayloadKeys.VALUE to prose),
        ),
      ),
    )
  }
}
