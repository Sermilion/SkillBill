package skillbill.engine.featuretask.slot.preplan

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.jsonValueContent
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class AgentPreplanStrategy(override val runner: PhaseRunner) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
        PhaseStepPolicy(
          mutating = false,
          relaunchOnInvalidOutput = true,
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
      valueContent = VALUE_CONTENT,
    )

  override fun runStep(
    run: PhaseRun,
    state: PhaseStepState,
  ): PhaseOutcome = runAgentStep(run, state)

  companion object {
    const val ID = "agent-preplan"

    private const val DIRECTIVE: String =
      "Produce the scaled pre-planning digest for the resolved feature size. Do not modify repository files " +
        "during this phase. Emit produced_outputs with a non-blank value string carrying the preplanning_digest " +
        "JSON (same fields as before, stuffed inside value); optional prompt may add a short directive when " +
        "non-blank. Do not forward the complete preplan envelope, a generic summary, or progress diagnostics."

    private val VALUE_CONTENT: String =
      jsonValueContent(
        innerJsonExample =
          "{ \"projection_kind\": \"preplanning_digest\",\n" +
            "  \"contract_version\": \"0.2\",\n" +
            "  \"affected_boundaries\": [\"<module or boundary touched>\"], \"patterns_and_decisions\": [],\n" +
            "  \"risks\": [\"<concrete risk>\"],\n" +
            "  \"rollout\": { \"flag_required\": false, \"flag_pattern\": \"none\",\n" +
            "    \"notes\": \"<rollout note, or N/A>\" },\n" +
            "  \"validation_strategy\": [\"<how the change is validated>\"],\n" +
            "  \"unresolved_questions\": [], \"evidence_refs\": [],\n" +
            "  \"selected_boundary_headings\": [\"<heading_id copied verbatim from the boundary catalog>\"] }\n",
        notes =
          "flag_pattern is one of none, simple_conditional, di_switch, legacy. Walk boundary_memory " +
            "headings for relevance; weave context into the stuffed object rather than listing headings only " +
            "outside value.",
      )
  }
}
