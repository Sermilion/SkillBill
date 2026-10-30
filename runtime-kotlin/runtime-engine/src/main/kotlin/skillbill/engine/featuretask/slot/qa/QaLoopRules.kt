package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.report.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.qa.report.hasQaOutput
import skillbill.engine.featuretask.qa.report.qaVerdictOf
import skillbill.engine.featuretask.runloop.core.containableOrThrow
import skillbill.engine.featuretask.slot.PhaseFailureClaimRules
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.PhaseLoopCapability
import skillbill.engine.featuretask.slot.PhaseLoopContext
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseSlotContainmentRules
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class QaLoopRules(
  private val outcomes: FeatureTaskQaOutcomeRecorder,
) : PhaseSlotContainmentRules<PhaseQaStepBinding> {
  private val containment = QaFailureContainment(outcomes)

  override fun containFailure(
    stepId: String,
    error: Exception,
    context: PhaseLoopContext,
    state: PhaseQaStepBinding,
  ): PhaseFailureContinuationOutput? = containment.contain(stepId, error, context, state)

  override fun onLoopCapExhausted(
    loopId: String,
    cap: Int,
    context: PhaseLoopContext,
    state: PhaseQaStepBinding,
  ) {
    val warning =
      when (loopId) {
        FeatureTaskRuntimePhaseWorkflowDefinition.QA_FIX_LOOP_ID ->
          "QA findings remain after $cap qa_fix attempts; the run continues and reports them."
        FeatureTaskRuntimePhaseWorkflowDefinition.QA_INVALIDATION_LOOP_ID -> STALE_WARNING
        else -> return
      }
    runCatching { recordStaleQa(context, warning) }.onFailure { recordingFailure ->
      RuntimeDiagnosticsBestEffortWarning.record(
        context.diagnostics,
        "QA loop cap warning could not be recorded [seam=qa_loop_cap, loop=$loopId].",
        recordingFailure.containableOrThrow(),
      )
    }
  }

  override fun crossSlotSettlement(
    stepId: String,
    context: PhaseLoopContext,
    state: PhaseQaStepBinding,
  ): FeatureTaskRuntimeVerdict? {
    if (stepId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY) return null
    val tested = testedTreeWithEvidence(state) ?: return null
    val current = context.gitOperations.repositoryTreeIdentity(context.request.repoRoot)
    val invalidations = context.loopIteration(FeatureTaskRuntimePhaseWorkflowDefinition.QA_INVALIDATION_LOOP_ID)
    val warning =
      when {
        !current.ok || current.value.isBlank() -> "$UNCHECKED_WARNING ${current.error}".trim()
        current.value == tested -> null
        invalidations < FeatureTaskRuntimePhaseWorkflowDefinition.MAX_QA_INVALIDATIONS ->
          return FeatureTaskRuntimeVerdict.QA_INVALIDATED
        else -> STALE_WARNING
      }
    warning?.let { recordStaleQa(context, it) }
    return null
  }

  private fun recordStaleQa(
    context: PhaseLoopContext,
    warning: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, warning)
    outcomes.recordWarning(context.request.workflowId, warning)
  }

  private fun testedTreeWithEvidence(state: PhaseQaStepBinding): String? {
    val produced = completedQaOutputs(state) ?: return null
    val skipped = !hasQaOutput(produced) || qaVerdictOf(produced) == QaVerdict.SKIPPED
    return (produced[QaPhaseOutputKeys.TREE_IDENTITY] as? String).takeUnless { skipped }
  }

  private fun completedQaOutputs(state: PhaseQaStepBinding): Map<String, Any?>? {
    val qa = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA
    if (!state.isStepCompleted(qa)) return null
    val envelope =
      state.completedStepPayload(qa)?.let(JsonCodec::parseObjectOrNull)?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: return null
    return JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
  }

  private companion object {
    const val STALE_WARNING =
      "QA results are stale: code changed after the last QA pass, and the " +
        "${FeatureTaskRuntimePhaseWorkflowDefinition.MAX_QA_INVALIDATIONS} QA re-runs that a changed tree allows " +
        "are used up. The run continues to history and commit."
    const val UNCHECKED_WARNING =
      "QA results may be stale: the runtime could not read the repository tree to compare it with the last QA " +
        "pass. The run continues to history and commit."
  }
}

internal object QaLoopCapability : PhaseLoopCapability, PhaseFailureClaimRules {
  override val loopRules: PhaseLoopRules get() = this

  override fun claimsFailure(
    stepId: String,
    context: PhaseLoopContext,
  ): Boolean = QaFailureContainment.claims(stepId, context)
}
