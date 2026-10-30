package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseCompletionRejectionHooks
import skillbill.engine.featuretask.slot.PhaseConsumerProjectionHooks
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseCompletedReceiptResume
import skillbill.engine.featuretask.slot.state.PhaseQualityGateStepBinding
import skillbill.engine.featuretask.slot.state.PhaseResumedCompletionInvalidation
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal object AgentValidateStepHooks :
  PhaseStepHooks<PhaseQualityGateStepBinding>,
  PhaseConsumerProjectionHooks,
  PhaseCompletionRejectionHooks<PhaseQualityGateStepBinding> {
  override val checksImmediateConsumerProjection: Boolean = true

  override val completionRejectionRule: String = FeatureTaskRuntimeFailureCode.INVALID_VALIDATION_TEST_PROBES.name

  override fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseQualityGateStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = ValidateProbeSettlement.rejection(run, context, outputMap)

  override fun interpretedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseQualityGateStepBinding,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput = ValidateProbeSettlement.settle(run, context, output)
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
