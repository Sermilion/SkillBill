package skillbill.engine.featuretask.slot

import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

class CompositionTestStrategy(
  override val slot: PhaseSlot,
  override val strategyId: String,
  override val steps: List<String>,
  override val optionalSteps: Set<String> = emptySet(),
  override val entryStep: String = steps.first(),
) : PhaseStrategy() {
  var stepPolicy = PhaseStepPolicy(false, false, false, false, false)

  override fun policyFor(stepId: String) = stepPolicy

  override fun directiveFor(stepId: String): String = error("Composition must not request a launch directive")

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> = error("Composition must not execute a step")
}
