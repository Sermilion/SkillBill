package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAuditOutputContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditVerdictRule.Companion.removedVerdictRejection
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.feature.FeatureTaskRuntimeAuditRemainingAcInterpretation
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeAuditRemainingAcResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

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
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? =
    (
      context as? PhaseAuditOutputContext
        ?: error("Audit settlement requires the accepted audit output context.")
    ).settleAuditRound(capture, attested, outputMap)

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
}
