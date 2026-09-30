package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object AuditImplementFixStep : PhaseStepHooks {
  override fun settleCompletedRound(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return null
    }
    val value = auditProseValue(outputMap).orEmpty()
    return if (AuditImplementFixPromptSections.endsWithCompletionMarker(value)) {
      null
    } else {
      AttemptResult.incompleteWork(
        operatorReason =
          "Audit repair returned completed without the '${AuditImplementFixPromptSections.COMPLETION_MARKER}' marker.",
        continuationReason =
          "Continue repairing every in-scope production gap in this repair run. Do not return completed " +
            "until the repair is complete and value ends with '${AuditImplementFixPromptSections.COMPLETION_MARKER}'.",
        fileManifest = capture.fileManifest,
        normalizedOutput = attested,
      )
    }
  }
}
