package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAuditOutputContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.audit.claim.NoChangeClaimResolution
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaimValidation
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object AcceptanceAuditRound : PhaseStepHooks {
  override val contextKind = PhaseStepHookContextKind.AUDIT

  private val NO_CHANGE_WORDS: Set<String> =
    setOf(
      FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue,
      FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED.wireValue,
    )

  override fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val claimed = NoChangeClaimResolution.activeClaimMap(context.progress) != null
    val verdict = outputMap[SharedPayloadKeys.VERDICT] as? String
    return when {
      claimed -> noChangeRoundRejection(context, outputMap)
      verdict in NO_CHANGE_WORDS ->
        "Audit verdict '$verdict' requires a no-change claim on the implementation output."
      else -> null
    }
  }

  override fun settleCompletedRound(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? {
    val auditContext =
      context as? PhaseAuditOutputContext
        ?: error("Audit settlement requires the accepted audit output context.")
    return auditContext.settleAuditRound(
      capture,
      attested,
      outputMap,
      null,
      noChangePauseFor(auditContext, outputMap),
    )
  }

  private fun noChangePauseFor(
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): FeatureTaskRuntimeNoChangePause? {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return null
    }
    if (outputMap[SharedPayloadKeys.VERDICT] != FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue) return null
    val valid = claimValidation(context) as? FeatureTaskRuntimeNoChangeClaimValidation.Valid ?: return null
    return FeatureTaskRuntimeNoChangePause.fromClaim(valid.claim, auditSummary = auditProseValue(outputMap).orEmpty())
  }

  private fun noChangeRoundRejection(
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return null
    }
    if (claimValidation(context) is FeatureTaskRuntimeNoChangeClaimValidation.Rejected) return null
    return when (outputMap[SharedPayloadKeys.VERDICT] as? String) {
      FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue -> null
      FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED.wireValue ->
        if (auditProseValue(outputMap) == null) {
          "Audit reported no_change_rejected without reasons. Give the specific per-criterion reasons in the value."
        } else {
          null
        }
      else -> "Audit on a no-change claim must report verdict no_change_confirmed or no_change_rejected."
    }
  }

  private fun claimValidation(context: PhaseStepOutputContext): FeatureTaskRuntimeNoChangeClaimValidation =
    NoChangeClaimResolution.validate(context.progress, context.request.runInvariants.acceptanceCriteria)

  override fun acceptedOutput(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return attested
    }
    return if (NoChangeClaimResolution.activeClaimMap(context.progress) != null) {
      noChangeAcceptedOutput(context, attested)
    } else {
      attested
    }
  }

  private fun noChangeAcceptedOutput(
    context: PhaseStepOutputContext,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val validation = claimValidation(context) as? FeatureTaskRuntimeNoChangeClaimValidation.Rejected ?: return attested
    return stampVerdict(
      attested,
      FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED,
      "No-change claim rejected: ${validation.reasons.joinToString(" ")}",
    )
  }

  private fun stampVerdict(
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    verdict: FeatureTaskRuntimeVerdict,
    prose: String? = null,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val envelope = normalizedOutput.envelopeWireMap().toMutableMap()
    envelope[SharedPayloadKeys.VERDICT] = verdict.wireValue
    if (prose != null) {
      val produced =
        JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty().toMutableMap()
      produced[SharedPayloadKeys.VALUE] =
        listOfNotNull(auditProseValue(normalizedOutput.envelopeWireMap()), prose).joinToString("\n\n")
      envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
    }
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
  }
}
