package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditVerdictRule.Companion.auditProseValue
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditVerdictRule.Companion.removedVerdictRejection
import skillbill.engine.featuretask.slot.state.PhaseStepState
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.feature.FeatureTaskRuntimeAuditRemainingAcInterpretation
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeAuditRemainingAcResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal object AcceptanceAuditRound : PhaseStepHooks {
  override fun completionRejection(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    removedVerdictRejection(outputMap)?.let { return it }
    val catalog = AcceptanceAuditCatalog.create(context.request.runInvariants.acceptanceCriteria)
    if (catalog is AcceptanceAuditCatalog.Unusable) return catalog.reason
    if (outputMap[SharedPayloadKeys.VERDICT] == FeatureTaskRuntimeVerdict.SATISFIED.wireValue &&
      FeatureTaskRuntimeAuditRemainingAcInterpretation.interpret(auditProseValue(outputMap)) !=
      FeatureTaskRuntimeAuditRemainingAcResult.EmptyRemainingList
    ) {
      return "Audit reported satisfied with remaining criteria. Emit an empty list only when every criterion is met."
    }
    return null
  }

  override fun settleCompletedRound(
    context: PhaseAttemptEnvironment,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? =
    with(context) {
      val run = capture.run
      if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
        return null
      }
      val finalResponse = auditProseValue(outputMap)
      return when (FeatureTaskRuntimeAuditRemainingAcInterpretation.interpret(finalResponse)) {
        FeatureTaskRuntimeAuditRemainingAcResult.MissingFinalResponse,
        FeatureTaskRuntimeAuditRemainingAcResult.WhitespaceOnlyFinalResponse,
        ->
          blockAuditWhitespaceOnlyFinalResponse(context, run, capture.iteration, capture.fileManifest)
        is FeatureTaskRuntimeAuditRemainingAcResult.RemainingCriteriaText -> {
          val reason = AcceptanceAuditProgress.rejectionReason(this, run, finalResponse.orEmpty())
          if (reason != null) {
            blockAuditNoProgress(context, capture, attested, reason)
          } else {
            null
          }
        }
        FeatureTaskRuntimeAuditRemainingAcResult.EmptyRemainingList -> null
      }
    }

  override fun acceptedOutput(
    context: PhaseAttemptEnvironment,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return attested
    }
    if (outputMap[SharedPayloadKeys.VERDICT] != null) return attested
    return when (FeatureTaskRuntimeAuditRemainingAcInterpretation.interpret(auditProseValue(outputMap))) {
      FeatureTaskRuntimeAuditRemainingAcResult.EmptyRemainingList -> stampSatisfiedVerdict(attested)
      else -> attested
    }
  }

  private fun stampSatisfiedVerdict(
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val envelope = normalizedOutput.envelopeWireMap().toMutableMap()
    envelope[SharedPayloadKeys.VERDICT] = FeatureTaskRuntimeVerdict.SATISFIED.wireValue
    return normalizedOutput.copy(
      envelope = envelope,
      canonicalJson = JsonCodec.mapToJsonString(envelope),
    )
  }

  private fun blockAuditWhitespaceOnlyFinalResponse(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    iteration: Int,
    fileManifest: FeatureTaskRuntimePhaseFileManifest?,
  ): AttemptResult =
    with(context) {
      AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
          request,
          state,
          recorder,
          observability,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason =
              "Audit completed with a whitespace-only remaining-criteria final response; the run blocks " +
                "rather than treating it as an empty list or launching a retry.",
            observability = observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
          ),
        ),
      )
    }

  private fun blockAuditNoProgress(
    context: PhaseAttemptEnvironment,
    capture: ValidatedOutputCapture,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    reason: String,
  ): AttemptResult =
    with(context) {
      AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
          request,
          state,
          recorder,
          observability,
          PhaseBlockRequest(
            run = capture.run,
            attemptCount = capture.iteration,
            reason = reason,
            observability = observability,
            payload = BlockAndPersistPayload(fileManifest = capture.fileManifest, normalizedOutput = normalizedOutput),
            failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          ),
        ),
      )
    }
}
