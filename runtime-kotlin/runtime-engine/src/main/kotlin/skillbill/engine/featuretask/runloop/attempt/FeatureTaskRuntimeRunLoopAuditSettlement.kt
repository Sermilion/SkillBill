package skillbill.engine.featuretask.runloop.attempt

import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditProgress
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.feature.FeatureTaskRuntimeAuditRemainingAcInterpretation
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeAuditRemainingAcResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal object FeatureTaskRuntimeRunLoopAuditSettlement {
  fun settleCompletedRound(
    context: PhaseCheckpointRemediationContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? {
    val run = capture.run
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return null
    }
    val finalResponse = auditProseValue(outputMap)
    return when (FeatureTaskRuntimeAuditRemainingAcInterpretation.interpret(finalResponse)) {
      FeatureTaskRuntimeAuditRemainingAcResult.MissingFinalResponse,
      FeatureTaskRuntimeAuditRemainingAcResult.WhitespaceOnlyFinalResponse,
      -> blockAuditWhitespaceOnlyFinalResponse(context, run, capture.iteration, capture.fileManifest)
      is FeatureTaskRuntimeAuditRemainingAcResult.RemainingCriteriaText -> {
        val reason = AcceptanceAuditProgress.rejectionReason(context, run, finalResponse.orEmpty())
        if (reason != null) blockAuditNoProgress(context, capture, attested, reason) else null
      }
      FeatureTaskRuntimeAuditRemainingAcResult.EmptyRemainingList -> null
    }
  }

  private fun blockAuditWhitespaceOnlyFinalResponse(
    context: PhaseCheckpointRemediationContext,
    run: PhaseRun,
    iteration: Int,
    fileManifest: FeatureTaskRuntimePhaseFileManifest?,
  ): AttemptResult =
    AttemptResult.settled(
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockStepInPhase(context,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason =
            "Audit completed with a whitespace-only remaining-criteria final response; the run blocks " +
              "rather than treating it as an empty list or launching a retry.",
          observability = context.observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
        ),
      ),
    )

  private fun blockAuditNoProgress(
    context: PhaseCheckpointRemediationContext,
    capture: ValidatedOutputCapture,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    reason: String,
  ): AttemptResult =
    AttemptResult.settled(
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockStepInPhase(context,
        PhaseBlockRequest(
          run = capture.run,
          attemptCount = capture.iteration,
          reason = reason,
          observability = context.observability,
          payload = BlockAndPersistPayload(fileManifest = capture.fileManifest, normalizedOutput = normalizedOutput),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        ),
      ),
    )
}
