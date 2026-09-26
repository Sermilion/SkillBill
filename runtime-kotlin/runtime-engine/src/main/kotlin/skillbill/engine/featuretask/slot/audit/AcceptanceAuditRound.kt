package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditVerdictRule.Companion.auditProseValue
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditVerdictRule.Companion.removedVerdictRejection
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.feature.FeatureTaskRuntimeAuditRemainingAcInterpretation
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeAuditRemainingAcResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object AcceptanceAuditRound : PhaseStepHooks {
  override fun onLaunch(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
  ) {
    if (context.state.resumedFromPriorProcess(run.phaseId)) {
      context.session.transitionAuditRetryFocusHint(null)
    }
  }

  override fun completionRejection(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = removedVerdictRejection(outputMap)

  override fun settleCompletedRound(
    context: FeatureTaskRuntimeRunLoopContext,
    capture: ValidatedOutputCapture,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? =
    with(context) {
      val run = capture.run
      if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
        return null
      }
      val finalResponse = auditProseValue(outputMap)
      return when (val interpretation = FeatureTaskRuntimeAuditRemainingAcInterpretation.interpret(finalResponse)) {
        FeatureTaskRuntimeAuditRemainingAcResult.MissingFinalResponse,
        FeatureTaskRuntimeAuditRemainingAcResult.WhitespaceOnlyFinalResponse,
        ->
          blockAuditWhitespaceOnlyFinalResponse(context, run, capture.iteration, capture.fileManifest)
        is FeatureTaskRuntimeAuditRemainingAcResult.RemainingCriteriaText -> {
          val branch = session.resolvedBranch
          if (branch != null) {
            val blocked =
              commitCompletedAuditRound(
                context,
                precedingPhaseId = run.phaseId,
                blockedReason =
                  auditRoundCommitBlockedReason(
                    branch,
                    "audit retry could not commit current changes",
                  ),
              )
            if (blocked != null) {
              return AttemptResult.settled(
                FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
                  request,
                  state,
                  recorder,
                  observability,
                  PhaseBlockRequest(
                    run = run,
                    attemptCount = capture.iteration,
                    reason = blocked,
                    observability = observability,
                    payload = BlockAndPersistPayload(fileManifest = capture.fileManifest),
                    failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
                  ),
                ),
              )
            }
          }
          AttemptResult.auditRetry(
            focusHint = interpretation.text,
            fileManifest = capture.fileManifest,
          )
        }
        FeatureTaskRuntimeAuditRemainingAcResult.EmptyRemainingList -> null
      }
    }

  override fun acceptedOutput(
    context: FeatureTaskRuntimeRunLoopContext,
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

  fun auditRemainingUnchangedBlockReason(): String =
    "Phase '${FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT}' returned the same remaining-criteria " +
      "text as the prior session; the run blocks rather than relaunching an audit that made no progress " +
      "on the remaining list."

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

  private fun commitCompletedAuditRound(
    context: FeatureTaskRuntimeRunLoopContext,
    precedingPhaseId: String,
    blockedReason: (
      String,
      String,
    ) -> String,
  ): String? =
    with(context) {
      val branch = requireNotNull(session.resolvedBranch)
      val currentBranch = phaseGates.gitOperations.currentBranch(request.repoRoot)
      if (currentBranch !is WorkflowGitOperationResult.Ok) {
        return blockedReason(branch, "current branch lookup failed: ${currentBranch.error}")
      }
      if (currentBranch.value.trim() != branch.trim()) {
        return blockedReason(branch, "current branch is '${currentBranch.value.trim()}'")
      }
      val established =
        with(FeatureTaskRuntimeRunLoopCheckpointRemediation) {
          FeatureTaskRuntimeRunLoopCheckpointRemediation.checkpointEstablished(
            context,
            precedingPhaseId = precedingPhaseId,
            loopId = null,
            intent = FeatureTaskRuntimeCheckpointMessage.INTENT_AUDITED_IMPLEMENTATION,
            blockedReason = blockedReason,
          )
        }
      return if (established) {
        null
      } else {
        session.blocked?.blockedReason
          ?: blockedReason(branch, "audit round commit could not be established")
      }
    }

  private fun auditRoundCommitBlockedReason(
    branch: String,
    detail: String,
  ): (String, String) -> String =
    { actualBranch, error ->
      auditReviewCheckpointBlockedReason(
        actualBranch.ifBlank { branch },
        error.ifBlank { detail },
      )
    }

  private fun blockAuditWhitespaceOnlyFinalResponse(
    context: FeatureTaskRuntimeRunLoopContext,
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
}
