package skillbill.engine.featuretask.runloop.attempt

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.PauseAndPersistInPhaseArgs
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.NO_CHANGE_PAUSE_REASON
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal object FeatureTaskRuntimeRunLoopAuditSettlement {
  fun settleCompletedRound(
    context: PhaseCheckpointRemediationContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    progressRejection: String?,
  ): AttemptResult? {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return null
    }
    return progressRejection?.let { blockAuditNoProgress(context, capture, attested, it) }
  }

  fun settleNoChangePause(
    settlement: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    pause: FeatureTaskRuntimeNoChangePause,
  ): AttemptResult {
    settlement.recorder.persistNoChangePause(settlement.request.workflowId, pause)
    return AttemptResult.settled(
      with(FeatureTaskRuntimeRunLoopPhaseBlocking) {
        settlement.pauseAndPersistInPhase(
          PauseAndPersistInPhaseArgs(
            run = capture.run,
            attemptCount = capture.iteration,
            reason = NO_CHANGE_PAUSE_REASON,
            observability = settlement.observability,
            fileManifest = capture.fileManifest,
          ),
        )
      },
    )
  }

  private fun blockAuditNoProgress(
    context: PhaseCheckpointRemediationContext,
    capture: ValidatedOutputCapture,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    reason: String,
  ): AttemptResult =
    AttemptResult.settled(
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockStepInPhase(
        context,
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
