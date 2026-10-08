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
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditVerdictRule.Companion.removedVerdictRejection
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
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

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
    removedVerdictRejection(outputMap)?.let { return it }
    val criteria = context.request.runInvariants.acceptanceCriteria
    val catalog = AcceptanceAuditCatalog.create(criteria)
    val claimed = NoChangeClaimResolution.activeClaimMap(context.progress) != null
    val verdict = outputMap[SharedPayloadKeys.VERDICT] as? String
    val scopeRejection =
      when {
        catalog is AcceptanceAuditCatalog.Unusable -> catalog.reason
        claimed -> noChangeRoundRejection(context, outputMap)
        verdict in NO_CHANGE_WORDS ->
          "Audit verdict '$verdict' requires a no-change claim on the implementation output."
        else ->
          AcceptanceAuditProgress.reopeningReason(
            criteria,
            auditProseValue(outputMap).orEmpty(),
            auditProseValue(context.progress.phase(run.phaseId).output?.normalizedOutput?.envelopeWireMap()),
          )
      }
    scopeRejection?.let { return it }
    val satisfiedWithRemaining =
      !claimed &&
        verdict == FeatureTaskRuntimeVerdict.SATISFIED.wireValue &&
        !AcceptanceAuditProgress.declaresComplete(criteria, auditProseValue(outputMap))
    return if (satisfiedWithRemaining) {
      "Audit reported satisfied with remaining criteria. Report satisfied only when every criterion is met."
    } else {
      null
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
    // A claim round judges cited evidence rather than the remaining-criteria prose, so the progress check skips it.
    val progressRejection =
      if (NoChangeClaimResolution.activeClaimMap(auditContext.progress) == null) {
        progressRejection(auditContext, capture, outputMap)
      } else {
        null
      }
    return auditContext.settleAuditRound(
      capture,
      attested,
      outputMap,
      progressRejection,
      noChangePauseFor(auditContext, outputMap),
    )
  }

  /** The pause a completed, confirmed, structurally valid claim settles to, or null when the round does not pause. */
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

  /** A claim round answers a no-change word; a structurally invalid claim is settled by [acceptedOutput]. */
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

  private fun progressRejection(
    context: PhaseAuditOutputContext,
    capture: ValidatedOutputCapture,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val finalResponse = auditProseValue(outputMap)
    val priorOutput = context.progress.phase(capture.run.phaseId).output?.normalizedOutput?.envelopeWireMap()
    val repaired =
      context.progress.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX).hasPriorRecord ||
        context.progress.loop(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID).iteration > 0
    val priorVerdict = priorOutput?.get(SharedPayloadKeys.VERDICT) as? String
    val freshBaseline = priorVerdict in NO_CHANGE_WORDS
    val outcome =
      AcceptanceAuditProgress.outcome(
        AcceptanceAuditProgressInput(
          criteria = context.request.runInvariants.acceptanceCriteria,
          text = finalResponse.orEmpty(),
          priorText = if (freshBaseline) null else auditProseValue(priorOutput),
          repaired = repaired && !freshBaseline,
          operatorReopened = context.operatorReopened,
          nonShrinkingRounds = context.nonShrinkingRounds,
          missingBaselineRounds = context.missingBaselineRounds,
        ),
      )
    return when (outcome) {
      AcceptanceAuditProgressOutcome.Advance -> null
      AcceptanceAuditProgressOutcome.RestartBaseline -> {
        if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() == WorkflowStepStatus.COMPLETED) {
          context.recordMissingBaselineRound(capture)
        }
        null
      }
      AcceptanceAuditProgressOutcome.MissingBaselineLimitReached -> {
        if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() == WorkflowStepStatus.COMPLETED) {
          context.recordMissingBaselineRound(capture)
        }
        AcceptanceAuditProgress.MISSING_BASELINE_LIMIT_REASON
      }
      AcceptanceAuditProgressOutcome.NonShrinking -> {
        if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() == WorkflowStepStatus.COMPLETED) {
          context.recordNonShrinkingRound(capture)
        }
        null
      }
      is AcceptanceAuditProgressOutcome.Rejected -> outcome.reason
    }
  }

  override fun acceptedOutput(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return attested
    }
    if (NoChangeClaimResolution.activeClaimMap(context.progress) != null) {
      return noChangeAcceptedOutput(context, attested)
    }
    if (outputMap[SharedPayloadKeys.VERDICT] != null) return attested
    val complete =
      AcceptanceAuditProgress.declaresComplete(
        context.request.runInvariants.acceptanceCriteria,
        auditProseValue(outputMap),
      )
    return if (complete) stampVerdict(attested, FeatureTaskRuntimeVerdict.SATISFIED) else attested
  }

  /**
   * A structurally invalid claim settles as a rejected round whose prose names every reason, so it repairs through the
   * same edge as any unmet round. A valid claim keeps the auditor's own no-change word.
   */
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
      produced[SharedPayloadKeys.VALUE] = prose
      envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
    }
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
  }
}
