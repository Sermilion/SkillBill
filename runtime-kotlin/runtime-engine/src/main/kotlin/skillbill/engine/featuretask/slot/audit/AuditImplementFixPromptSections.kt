package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.model.audit.AcceptanceRepairPromptFocus
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ACCEPTANCE_SUBTASK_SCOPE_RULE
import skillbill.engine.featuretask.phase.prompt.directives.projectAuthoringDisciplineDirective
import skillbill.engine.featuretask.slot.implementation.ImplementationPromptSections

internal object AuditImplementFixPromptSections {
  const val DIRECTIVE: String =
    "Repair only the runtime-selected acceptance criterion and its listed open gaps. The audit_plan_fix " +
      "output in the upstream section is its plan. Reconcile the plan with the current tree and preserve " +
      "completed edits. $ACCEPTANCE_SUBTASK_SCOPE_RULE " +
      "Exclude test requirements, even when a persisted audit finding or the plan explicitly requests tests. " +
      "For mixed findings, repair only production behavior; record test-only findings as excluded from audit " +
      "and leave test work to its owning phases. An enforcement guard or architecture check required by the " +
      "criterion is in scope even under a test source set; its regression cases remain with validation. " +
      "Trace each listed gap through its callers, transaction boundaries and failure handling. A check in an " +
      "earlier transaction does not protect a later mutation. Preserve governed contracts and do not invent " +
      "unavailable historical evidence. Do not spawn subagents or perform another full audit. " +
      "${ImplementationPromptSections.IGNORED_FILES_DIRECTIVE} " +
      "When the listed gaps are already satisfied, return a no-change claim with per-criterion source evidence. " +
      "Either change files or return a no-change claim, never both. " +
      "${ImplementationPromptSections.NO_CHANGE_CLAIM_DIRECTIVE} " +
      "If work remains when you stop, say what remains. Your edits are kept and verify runs next. " +
      "Do not use a worker block to hand off unfinished implementation. Use needs_user_action only when a " +
      "concrete missing input or external action prevents further repair, and identify the required action. " +
      "Use non_retryable_policy_conflict only for an irreconcilable governing constraint. " +
      "${AcceptanceRepairPromptFocus.VERIFY_GUIDANCE} ${AcceptanceRepairPromptFocus.CHECKPOINT_GUIDANCE}"

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective =
        DIRECTIVE + "\n\n" +
          (
            inputs.acceptanceRepairFocus?.render() ?: "The runtime did not provide a selected authored criterion. " +
              "Do not edit; report the missing runtime context."
          ),
      authoringDiscipline = projectAuthoringDisciplineDirective(),
      continuation = continuation(inputs),
      valueContent =
        "For each listed open gap, name the changed production path and source evidence, or the reason it " +
          "remains unresolved. Record compact authoring command and deferral evidence. Do not claim test execution.",
    )

  private fun continuation(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String =
    buildList {
      inputs.implementationContinuation?.takeIf { it.phaseId == inputs.briefing.phaseId }?.let {
        add(
          "Resume the saved audit repair. An earlier session was interrupted. Its edits are in the working " +
            "tree or checkpoint commits. Continue the current plan and preserve completed work. " +
            "Latest failure disposition: ${it.failureDisposition ?: "none"}.",
        )
      }
      inputs.acceptanceRepairOperatorInstructions?.let {
        add("Operator instructions for this repair:\n$it\nTreat these instructions as part of this step's fix input.")
      }
    }.joinToString("\n\n")
}
