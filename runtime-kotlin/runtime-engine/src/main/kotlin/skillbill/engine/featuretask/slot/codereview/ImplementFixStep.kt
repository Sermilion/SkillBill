package skillbill.engine.featuretask.slot.codereview

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class ImplementFixStep : PhaseStepHooks {
  val policy =
    PhaseStepPolicy(
      mutating = true,
      relaunchOnInvalidOutput = true,
      singleAgentSession = false,
      readOnlyIdle = false,
      fileMutating = true,
      generationScoped = true,
    )

  override val fingerprintsCompletedRepository: Boolean = true

  override fun handoffFindingVerdicts(state: PhaseRunState): List<ReviewFindingVerdict> {
    val review = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    val envelope =
      state.completedStepEnvelope(review)
        ?: state.completedStepPayload(review)
          ?.let { JsonCodec.parseObjectOrNull(it) }
          ?.let { JsonCodec.jsonElementToValue(it) }
          ?.let(JsonCodec::anyToStringAnyMap)
        ?: return emptyList()
    return state.recordedFindingVerdicts(envelope)
  }

  override fun settleCompletedOutput(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = ImplementFixReceipt.settle(context, state, outputMap)
}
