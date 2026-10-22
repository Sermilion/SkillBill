package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object AuditImplementFixStep : PhaseStepHooks<PhaseAcceptedStepExecution> {
  override val serialAcceptanceRepairStep: Boolean = true

  override fun settleCompletedRound(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? {
    if (capture.run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX) return null
    val verdict = outputMap[SharedPayloadKeys.VERDICT]
    val idleProgress =
      verdict == FeatureTaskRuntimeVerdict.PROGRESS.wireValue && capture.fileManifest.introduced.isEmpty()
    if (verdict != FeatureTaskRuntimeVerdict.NO_PROGRESS.wireValue && !idleProgress) {
      return null
    }
    return AttemptResult.incompleteWork(
      if (idleProgress) {
        "The repair report says progress, but the round changed no source files."
      } else {
        "The repair report says production work remains."
      },
      "Settle the unfinished repair against the selected criterion's failure budget before continuing.",
      capture.fileManifest,
      attested,
    )
  }
}
