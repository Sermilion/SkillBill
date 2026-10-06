package skillbill.engine.featuretask.slot.preplan

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class AgentPreplanStrategy : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = false,
          generationScoped = false,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.PREPLAN
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return DIRECTIVE
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = directiveFor(stepId),
      ceremonyLine =
        "Apply ${ceremonyScalingOf(inputs.briefing).preplanCeremony.promptLabel}. Keep the gate real: identify " +
          "concrete scope, affected boundaries, risks, and unknowns at the requested depth.",
      stepContext = if (inputs.suppressDecomposition) "" else featureSpecIntake,
    )

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = runAgentStep(run, state)

  companion object {
    const val ID = "agent-preplan"

    private const val FEATURE_SPEC_INTAKE =
      "/skillbill/engine/featuretask/slot/preplan/feature-spec-intake.md"

    private val featureSpecIntake: String by lazy { directiveResource(FEATURE_SPEC_INTAKE).trimEnd() }

    private const val DIRECTIVE: String =
      "Produce the scaled pre-planning digest for the resolved feature size, as prose. Do not modify repository " +
        "files during this phase. This is the feature's only discovery: the plan phase plans every subtask " +
        "from this digest and never reads the repository. Write what the plan phase needs: the boundaries the " +
        "change touches, the patterns and decisions that apply, concrete risks, and rollout and validation " +
        "considerations. Carry the evidence each subtask spec will cite: exact paths, symbols, signatures, and " +
        "type hierarchies of the code this task changes, the tests that exercise that code, and the existing " +
        "patterns those files already follow. Read only files this task directly touches. Do not read unrelated " +
        "modules, docs, or tests, and do not read dependency jars, Gradle caches, generated sources, or library " +
        "internals. The boundary catalog in this briefing is the heading list. Judge each heading from that " +
        "heading text. An irrelevant heading stays unread. Open a history or decisions file only for a heading " +
        "that bears on this task, and read only that heading's section. Never read a history or decisions file " +
        "from start to finish. Leave open any decision this task's own code cannot answer, including dependency, " +
        "account, and environment questions, each with the facts that bear on it and the option you recommend. " +
        "Name a cited heading by its heading_id exactly as the boundary catalog spells it. Do not forward " +
        "progress diagnostics or a generic summary."
  }
}
