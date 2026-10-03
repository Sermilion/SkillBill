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
        "Write the repair plan in execution order. Start each independent gap with a heading " +
          "'### <criterion ID>', using the audit's criterion ID. Repeat the heading for separate gaps " +
          "under the same criterion. Each item must contain these four labels with concrete nonempty " +
          "text on the label line: 'Gap:', 'Production path:', 'Changes:', and 'Closure evidence:'. " +
          "Changes names the edits, their order, and dependencies. Closure evidence names the " +
          "production behavior and source inspection that must hold after execution, without " +
          "claiming tests ran. Cover every remaining production criterion and every independent " +
          "finding under it. Do not plan repairs for satisfied criteria. This value is the persisted " +
          "plan delivered to audit_implement_fix; no repair happens in this step.",
    )
}
