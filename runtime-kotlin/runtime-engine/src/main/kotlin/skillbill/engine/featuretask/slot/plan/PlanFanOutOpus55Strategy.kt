package skillbill.engine.featuretask.slot.plan

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.appendWhenOpus
import skillbill.engine.featuretask.slot.opus55Directive
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.OPUS_55_STRATEGY_ID_SUFFIX
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class PlanFanOutOpus55Strategy(
  private val canonical: PlanFanOutStrategy,
) : PhaseStrategy() {
  override val plansInFanOut: Boolean = canonical.plansInFanOut
  override val slot: PhaseSlot = canonical.slot
  override val strategyId: String = ID
  override val semanticRevision: Int = canonical.semanticRevision
  override val steps: List<String> = canonical.steps
  override val entryStep: String = canonical.entryStep

  override fun policyFor(stepId: String): PhaseStepPolicy = canonical.policyFor(stepId)

  override fun directiveFor(stepId: String): String = canonical.directiveFor(stepId)

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    val subtasks = stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
    return canonical
      .promptSections(stepId, inputs)
      .appendWhenOpus(inputs.stepProfile, if (subtasks) SUBTASKS_DIRECTIVE else PLAN_DIRECTIVE)
  }

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> =
    canonical.briefingInvariantFields(stepId)

  override fun stepHooks(stepId: String): PhaseStepHooks = canonical.stepHooks(stepId)

  override fun resumeRules(stepId: String): PhaseResumeRules = canonical.resumeRules(stepId)

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = canonical.runStep(run, state)

  companion object {
    const val ID: String = PlanFanOutStrategy.ID + OPUS_55_STRATEGY_ID_SUFFIX
    private val PLAN_DIRECTIVE: String =
      opus55Directive("/skillbill/engine/featuretask/slot/plan/opus-5-5-agent-plan.md")
    private val SUBTASKS_DIRECTIVE: String =
      opus55Directive("/skillbill/engine/featuretask/slot/plan/opus-5-5-goal-plan-fan-out.md")
  }
}
