package skillbill.engine.featuretask.slot.standalonereview

import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.appendWhenOpus
import skillbill.engine.featuretask.slot.opus55Directive
import skillbill.workflow.taskruntime.model.skeleton.OPUS_55_STRATEGY_ID_SUFFIX

class DelegatedStandaloneReviewOpus55Strategy(
  runner: PhaseRunner,
  reviewRunner: ParallelCodeReviewRunner,
) : DelegatedStandaloneReviewStrategy(runner, reviewRunner) {
  override val strategyId: String = ID

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = super.promptSections(stepId, inputs).appendWhenOpus(inputs.stepProfile, DIRECTIVE)

  companion object {
    const val ID: String = DelegatedStandaloneReviewStrategy.ID + OPUS_55_STRATEGY_ID_SUFFIX
    internal val DIRECTIVE: String =
      opus55Directive("/skillbill/engine/featuretask/slot/standalonereview/opus-5-5-delegated.md")
  }
}
