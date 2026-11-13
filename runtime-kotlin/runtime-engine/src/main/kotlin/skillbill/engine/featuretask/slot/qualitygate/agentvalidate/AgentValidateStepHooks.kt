package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.slot.PhaseConsumerProjectionHooks
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.state.PhaseCompletedReceiptResume
import skillbill.engine.featuretask.slot.state.PhaseQualityGateStepBinding
import skillbill.engine.featuretask.slot.state.PhaseResumedCompletionInvalidation
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal object AgentValidateStepHooks :
  PhaseStepHooks<PhaseQualityGateStepBinding>,
  PhaseConsumerProjectionHooks {
  override val checksImmediateConsumerProjection: Boolean = true
}

internal object AgentValidateResumeRules : PhaseCompletedReceiptResume, PhaseResumedCompletionInvalidation {
  override val requiresValidCompletedOutput = true

  override fun invalidatesResumedCompletion(
    record: FeatureTaskRuntimePhaseRecord,
    output: () -> FeatureTaskRuntimePhaseOutput?,
  ): Boolean {
    val envelope = output()?.normalizedOutput?.envelopeWireMap()
    return envelope == null ||
      (envelope[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED
  }
}
