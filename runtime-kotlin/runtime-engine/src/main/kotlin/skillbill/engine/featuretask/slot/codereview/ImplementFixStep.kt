package skillbill.engine.featuretask.slot.codereview

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class ImplementFixStep : PhaseStepHooks {
  val policy =
    PhaseStepPolicy(
      mutating = true,
      singleAgentSession = false,
      readOnlyIdle = false,
      fileMutating = true,
      generationScoped = true,
      extendsOwnedInventory = true,
    )

  override val fingerprintsCompletedRepository: Boolean = true

  override fun handoffFindingVerdicts(state: PhaseImplementFixStepBinding): List<ReviewFindingVerdict> {
    val review = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    val envelope =
      state.completedStepEnvelope(review)
        ?: state
          .completedStepPayload(review)
          ?.let { JsonCodec.parseObjectOrNull(it) }
          ?.let { JsonCodec.jsonElementToValue(it) }
          ?.let(JsonCodec::anyToStringAnyMap)
        ?: return emptyList()
    return state.recordedFindingVerdicts(envelope)
  }

  override fun settleCompletedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = ImplementFixReceipt.settle(context, state as PhaseImplementFixStepBinding, outputMap)
}
