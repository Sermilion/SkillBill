package skillbill.engine.featuretask.slot.implementation

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.PhaseEntrySettlement
import skillbill.engine.featuretask.slot.PhaseLoopContext
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.audit.claim.NoChangeClaimResolution
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

/** Settles simplify without a launch when the implement step returned a no-change claim, so there is nothing to clean up. */
internal object ImplementThenSimplifyLoopRules : PhaseLoopRules {
  private const val IMPLEMENT = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
  private const val SIMPLIFY = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY
  private const val SKIP_PROSE =
    "Simplify skipped: the implement step returned a no-change claim; no files were changed."

  override fun settleWithoutLaunch(
    stepId: String,
    context: PhaseLoopContext,
    state: PhaseAcceptedStepExecution,
  ): PhaseEntrySettlement? {
    if (stepId != SIMPLIFY || !implementClaimsNoChange(state)) return null
    return runCatching { state.settleRuntimeAuthoredCompletion(SIMPLIFY, skipEnvelope()) }.fold(
      onSuccess = { PhaseEntrySettlement.Completed(FeatureTaskRuntimeVerdict.ADVANCE) },
      onFailure = { error ->
        PhaseEntrySettlement.Blocked(
          "Skipped simplify could not persist its completion: ${error.message.orEmpty()}",
        )
      },
    )
  }

  private fun implementClaimsNoChange(state: PhaseAcceptedStepExecution): Boolean =
    NoChangeClaimResolution.claimOf(
      state.completedStepEnvelope(IMPLEMENT)?.get(SharedPayloadKeys.PRODUCED_OUTPUTS),
    ) != null

  private fun skipEnvelope(): NormalizedFeatureTaskRuntimePhaseOutput =
    NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        linkedMapOf<String, Any?>(
          SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          SharedPayloadKeys.PHASE_ID to SIMPLIFY,
          SharedPayloadKeys.STATUS to STATUS_COMPLETED,
          SharedPayloadKeys.SUMMARY to SKIP_PROSE,
          SharedPayloadKeys.PRODUCED_OUTPUTS to linkedMapOf<String, Any?>(SharedPayloadKeys.VALUE to SKIP_PROSE),
        ),
      ),
    )
}
