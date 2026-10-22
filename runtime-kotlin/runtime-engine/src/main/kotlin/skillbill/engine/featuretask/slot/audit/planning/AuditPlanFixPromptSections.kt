package skillbill.engine.featuretask.slot.audit.planning

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections

internal object AuditPlanFixPromptSections {
  const val DIRECTIVE: String =
    "Plan how to repair every independent production gap in the runtime-selected authored criterion " +
      "from the latest persisted audit. Inspect the current source " +
      "and original feature plan before proposing changes. Compare the latest gap with the retained " +
      "unsuccessful repair evidence. Identify why earlier approaches failed and explain what changes in " +
      "this plan; do not repeat a rejected approach without source evidence that resolves its rejection. " +
      "Check that each proposed change satisfies the authored requirement itself: a reconstruction, " +
      "fallback, warning, or renamed abstraction cannot substitute for behavior the criterion requires. " +
      "When required historical data never existed or is no longer recoverable, identify that constraint " +
      "explicitly instead of inventing replacement evidence or promising to recover it. " +
      "Do not edit files, spawn subagents, compile, " +
      "build, run tests, format, lint, or run repository checks. This step plans repairs; the runtime " +
      "saves the plan before audit_implement_fix executes it. The latest named gap is an example of a " +
      "remaining defect class, not the whole plan. Inspect current source for sibling production paths " +
      "of the same forbidden pattern under this criterion and plan every remaining sibling in this step. " +
      "Split independent gaps under one " +
      "criterion into separate plan items. For each item, identify the missing behavior, actual " +
      "consumer and owning production paths, exact changes and their execution order, dependencies " +
      "on other items, and source evidence that will prove closure. Preserve completed work and " +
      "governed contracts. Before choosing edits, state the criterion's required invariant, its owner, " +
      "and the authoritative source for each fact. Distinguish immutable facts, references owned by " +
      "different callers, and mutable progress when the criterion uses them. Reconcile those meanings " +
      "across all affected readers and writers before proposing local checks. Trace first execution, " +
      "repeated execution, replay or resume after state advances, a new attempt or generation, and " +
      "failure through the actual consumers. For each applicable sequence, identify expected state " +
      "and identity, the current violation, and how the proposed changes preserve the invariant. " +
      "Explain why any sequence does not apply. Do not invent a counter, identifier, or fallback " +
      "merely to bypass a refusal or collision; derive it from its owning contract. " +
      "Exclude test requirements as audit does. An enforcement guard required " +
      "by a criterion is production work even under a test source set; its regression cases remain " +
      "with validation. Do not substitute an intent statement or the original feature plan for a " +
      "repair plan. Explain how each change closes its specific gap. If a concrete missing input " +
      "prevents a repair plan, report blocked with its failure disposition and required action. " +
      "When the latest audit output begins with `Operator instructions:`, the operator rejected an audit-confirmed " +
      "no-change claim; treat those instructions as part of this step's fix input and the governing repair scope."

  fun sections(
    currentCriterion: String? = null,
    priorRepairFailures: String? = null,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective =
        DIRECTIVE +
          (
            currentCriterion?.let {
              "\n\nRuntime-selected authored criterion and retained budget:\n$it\nKeep this plan " +
                "on this criterion. The latest gap is an example of its remaining defect class; " +
                "plan every remaining sibling of that class in this step."
            } ?: (
              "\n\nThe runtime did not provide a selected authored criterion. " +
                "Do not plan a broad repair; report the missing runtime context."
            )
          ) + priorRepairFailures?.let { "\n\n$it" }.orEmpty(),
      valueContent =
        "Write the repair plan as prose in execution order. Explain each reported production gap, " +
          "the production paths involved, the changes needed, and any dependencies between fixes. " +
          "Describe how the changes close each gap. Account for completed edits in the current " +
          "tree. Cover every remaining sibling of the selected criterion's defect class, not only " +
          "the last named gap. Shared prerequisite edits are allowed, but other criteria need their own audit. " +
          "This value is the persisted plan delivered to audit_implement_fix; no repair happens " +
          "in this step.",
    )
}
