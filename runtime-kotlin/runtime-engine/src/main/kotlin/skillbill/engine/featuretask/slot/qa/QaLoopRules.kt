package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.featuretask.qa.receipt.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.slot.PhaseLoopContext
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.error.featuretask.qa.qaRepositoryUnreadable
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object QaLoopRules : PhaseLoopRules {
  override fun crossSlotSettlement(
    stepId: String,
    context: PhaseLoopContext,
    state: PhaseAcceptedStepExecution,
  ): FeatureTaskRuntimeVerdict? {
    if (stepId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY) return null
    val tested = testedTreeWithEvidence(state) ?: return null
    val current = context.gitOperations.repositoryTreeIdentity(context.request.repoRoot)
    if (!current.ok || current.value.isBlank()) throw qaRepositoryUnreadable(current.error)
    return FeatureTaskRuntimeVerdict.QA_INVALIDATED.takeIf { current.value != tested }
  }

  private fun testedTreeWithEvidence(state: PhaseAcceptedStepExecution): String? {
    val produced = completedQaOutputs(state) ?: return null
    val receipt = JsonCodec.anyToStringAnyMap(produced[QaPhaseOutputKeys.RECEIPT])
    val skipped = receipt == null || receipt[QaReceiptPayloadKeys.VERDICT] == QaVerdict.SKIPPED.wireValue
    return (produced[QaPhaseOutputKeys.TREE_IDENTITY] as? String).takeUnless { skipped }
  }

  private fun completedQaOutputs(state: PhaseAcceptedStepExecution): Map<String, Any?>? {
    val qa = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA
    if (!state.isStepCompleted(qa)) return null
    val envelope =
      state.completedStepPayload(qa)?.let(JsonCodec::parseObjectOrNull)?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: return null
    return JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
  }
}
