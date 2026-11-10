package skillbill.engine.featuretask.slot.audit.planning

import skillbill.engine.featuretask.model.audit.AcceptanceRepairPromptFocus
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ACCEPTANCE_SUBTASK_SCOPE_RULE
import skillbill.engine.featuretask.slot.plan.planningCapabilityClosureDirective

internal object AuditPlanFixPromptSections {
  const val DIRECTIVE: String =
    "Plan a repair for the runtime-selected criterion's listed open gaps. Keep this plan on this criterion. " +
      "Use current source to identify the necessary production changes and their execution order, dependencies " +
      "and source evidence for closure. Do not edit files, spawn subagents, compile, build, run tests, format, " +
      "lint or run repository checks. $ACCEPTANCE_SUBTASK_SCOPE_RULE " +
      "Exclude test requirements. An enforcement guard required by the criterion is production work even under " +
      "a test source set; its regression cases remain with validation. Preserve completed work and governed " +
      "contracts. Do not invent unavailable historical evidence. If a concrete missing input prevents a repair " +
      "plan, report blocked with its failure disposition and required action. " +
      "${AcceptanceRepairPromptFocus.VERIFY_GUIDANCE} ${AcceptanceRepairPromptFocus.CHECKPOINT_GUIDANCE}"

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective =
        DIRECTIVE + "\n\n" +
          (
            inputs.acceptanceRepairFocus?.render() ?: "The runtime did not provide a selected authored criterion. " +
              "Do not plan a broad repair; report the missing runtime context."
          ),
      stepContext = planningCapabilityClosureDirective,
      continuation =
        inputs.acceptanceRepairOperatorInstructions
          ?.let {
            "Operator instructions for this repair:\n$it\nTreat these instructions as part of this step's fix input."
          }
          .orEmpty(),
      valueContent = "Write the repair plan in execution order and explain how each change closes a listed open gap.",
    )
}
