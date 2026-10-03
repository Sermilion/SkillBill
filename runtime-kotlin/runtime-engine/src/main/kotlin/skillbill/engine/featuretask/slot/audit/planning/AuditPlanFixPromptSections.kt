package skillbill.engine.featuretask.slot.audit.planning

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections

internal object AuditPlanFixPromptSections {
  const val DIRECTIVE: String =
    "Plan how to repair every production gap in the latest persisted audit. Inspect the current source " +
      "and original feature plan before proposing changes. Do not edit files, spawn subagents, compile, " +
      "build, run tests, format, lint, or run repository checks. This step plans repairs; the runtime " +
      "saves the plan before audit_implement_fix executes it. Split independent gaps under one " +
      "criterion into separate plan items. For each item, identify the missing behavior, actual " +
      "consumer and owning production paths, exact changes and their execution order, dependencies " +
      "on other items, and source evidence that will prove closure. Preserve completed work and " +
      "governed contracts. Exclude test requirements as audit does. An enforcement guard required " +
      "by a criterion is production work even under a test source set; its regression cases remain " +
      "with validation. Do not substitute an intent statement or the original feature plan for a " +
      "repair plan. Explain how each change closes its specific gap. If a concrete missing input " +
      "prevents a repair plan, report blocked with its failure disposition and required action."

  fun sections(): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = DIRECTIVE,
      valueContent =
        "Write the repair plan as prose in execution order. Explain each reported production gap, " +
          "the production paths involved, the changes needed, and any dependencies between fixes. " +
          "Describe how the changes close each gap. Account for completed edits in the current " +
          "tree. Cover the remaining production criteria and independent findings under them. " +
          "This value is the persisted plan delivered to audit_implement_fix; no repair happens " +
          "in this step.",
    )
}
