package skillbill.engine.featuretask.slot.qualitygate.packbuild

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseQualityGateOperation
import skillbill.engine.featuretask.slot.PhaseReportedGate
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategyStatusProjection
import skillbill.engine.featuretask.slot.appendWhenOpus
import skillbill.engine.featuretask.slot.opus55Directive
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.skeleton.OPUS_55_STRATEGY_ID_SUFFIX
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

class PackBuildOpus55Strategy : PhaseStrategyStatusProjection() {
  private val canonical = PackBuildStrategy()

  override val qualityGateOperation: PhaseQualityGateOperation? = canonical.qualityGateOperation
  override val slot: PhaseSlot = canonical.slot
  override val strategyId: String = ID
  override val steps: List<String> = canonical.steps
  override val entryStep: String = canonical.entryStep

  override fun resumeRules(stepId: String): PhaseResumeRules = canonical.resumeRules(stepId)

  override fun policyFor(stepId: String): PhaseStepPolicy = canonical.policyFor(stepId)

  override fun directiveFor(stepId: String): String = canonical.directiveFor(stepId)

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = canonical.promptSections(stepId, inputs).appendWhenOpus(inputs.stepProfile, DIRECTIVE)

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = canonical.runStep(run, state)

  override fun stepHooks(stepId: String): PhaseStepHooks = canonical.stepHooks(stepId)

  override fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? = canonical.currentExecution(stepId, context)

  override fun reportedGate(stepId: String): PhaseReportedGate? = canonical.reportedGate(stepId)

  companion object {
    const val ID: String = PackBuildStrategy.ID + OPUS_55_STRATEGY_ID_SUFFIX
    private val DIRECTIVE: String =
      opus55Directive("/skillbill/engine/featuretask/slot/qualitygate/opus-5-5-pack-build.md")
  }
}
