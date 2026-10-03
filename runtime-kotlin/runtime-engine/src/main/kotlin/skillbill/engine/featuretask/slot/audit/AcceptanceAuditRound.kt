package skillbill.engine.featuretask.slot.audit

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
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object AcceptanceAuditRound : PhaseStepHooks {
  override val contextKind = PhaseStepHookContextKind.AUDIT

  override fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    removedVerdictRejection(outputMap)?.let { return it }
    val catalog = AcceptanceAuditCatalog.create(context.request.runInvariants.acceptanceCriteria)
    val scopeRejection =
      if (catalog is AcceptanceAuditCatalog.Unusable) {
        catalog.reason
      } else {
        AcceptanceAuditProgress.reopeningReason(
          context.request.runInvariants.acceptanceCriteria,
          auditProseValue(outputMap).orEmpty(),
          auditProseValue(context.progress.phase(run.phaseId).output?.normalizedOutput?.envelopeWireMap()),
        )
      }
    scopeRejection?.let { return it }
    if (outputMap[SharedPayloadKeys.VERDICT] == FeatureTaskRuntimeVerdict.SATISFIED.wireValue &&
      !AcceptanceAuditProgress.declaresComplete(
        context.request.runInvariants.acceptanceCriteria,
        auditProseValue(outputMap),
      )
    ) {
      return "Audit reported satisfied with remaining criteria. Report satisfied only when every criterion is met."
    }
    return null
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
    val progressRejection = progressRejection(auditContext, capture, outputMap)
    return auditContext.settleAuditRound(capture, attested, outputMap, progressRejection)
  }

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
    val outcome =
      AcceptanceAuditProgress.outcome(
        AcceptanceAuditProgressInput(
          criteria = context.request.runInvariants.acceptanceCriteria,
          text = finalResponse.orEmpty(),
          priorText = auditProseValue(priorOutput),
          repaired = repaired,
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
    if (outputMap[SharedPayloadKeys.VERDICT] != null) return attested
    val complete =
      AcceptanceAuditProgress.declaresComplete(
        context.request.runInvariants.acceptanceCriteria,
        auditProseValue(outputMap),
      )
    return if (complete) stampSatisfiedVerdict(attested) else attested
  }

  private fun stampSatisfiedVerdict(
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val envelope = normalizedOutput.envelopeWireMap().toMutableMap()
    envelope[SharedPayloadKeys.VERDICT] = FeatureTaskRuntimeVerdict.SATISFIED.wireValue
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
  }
}
