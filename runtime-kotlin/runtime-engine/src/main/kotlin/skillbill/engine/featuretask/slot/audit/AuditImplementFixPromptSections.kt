package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.projectAuthoringDisciplineDirective
import skillbill.engine.featuretask.slot.implementation.ImplementationPromptSections

internal object AuditImplementFixPromptSections {
  const val DIRECTIVE: String =
    "Execute the persisted audit_plan_fix repair plan in its declared order. The supplied upstream output " +
      "contains that plan and the latest audit findings. Reconcile each planned item against the current " +
      "tree before editing, preserve work already completed, and implement its proposed production changes. " +
      "Compare the plan with the retained rejection evidence before editing. Do not recreate an approach " +
      "a prior audit rejected unless current source evidence resolves the reason for rejection. If the " +
      "saved plan repeats that defect, adjust it within this criterion and explain the correction. " +
      "Do not invent unavailable historical data or substitute reconstructed evidence for an original " +
      "the criterion requires. Report closure evidence for every plan item and explain any " +
      "source-backed adjustment. Do not " +
      "replace the repair plan with the original feature plan or silently omit a planned gap. " +
      "Repair every independent production gap for the runtime-selected authored criterion " +
      "reported by the latest audit in the supplied upstream output. " +
      "Exclude test requirements, even when a persisted audit finding or the plan explicitly requests tests. " +
      "Do not add or repair tests to close an audit criterion. For mixed findings, repair only production " +
      "behavior; record test-only findings as excluded from audit and leave test work to its owning phases. " +
      "An enforcement guard or architecture check whose implementation an acceptance criterion requires is " +
      "in scope even when it lives under a test source set: repair the guard implementation the audit names, " +
      "but leave its example and regression test cases to their owning phases. An earlier audit or repair " +
      "calling a criterion test-only does not exclude a guard implementation that the criterion requires. " +
      "A criterion may contain several independent production gaps: account for each before editing and address " +
      "all of them in this repair run. A sibling of a planned defect class found while tracing callers is " +
      "in-scope for this run; do not defer it to the next audit. Shared prerequisite edits are allowed, but other " +
      "criteria need their own audit. Use the plan and current repository to implement the missing behavior " +
      "in production. Trace each reported production path through its callers, transaction boundaries, " +
      "and failure handling. A check in an earlier transaction does not protect a later mutation. Reconcile " +
      "already-applied edits and preserve completed work and governed contracts. Before reporting completion, " +
      "trace each supplied finding from its actual consumer through every reachable helper to the named " +
      "operation, and finish any remaining in-scope repair. Then do a separate read-only source closure " +
      "pass against the authored invariant and retained failed-repair counterexamples. Trace the changed " +
      "readers and writers through first execution, repeated execution, replay or resume after state " +
      "advances, a new attempt or generation, and failure. Check each applicable sequence's expected " +
      "state and identity against the final source, including lookups, equality checks, copied references " +
      "and transaction boundaries. Explain inapplicable sequences. Resolve contradictory meanings " +
      "across paths before claiming completion; another local guard does not close an inconsistent " +
      "state model. Include the concrete counterexample and final consumer-to-writer source evidence " +
      "in the closure report. Renaming a getter, introducing a forwarding " +
      "interface, or moving mutable authority behind a recoverable cast does not close an access finding. " +
      "Do not defer " +
      "known missing behavior to the next audit or validation. Do not spawn subagents or perform another " +
      "full audit. Do not compile, build, run tests, or run a full repository check, because build and validate " +
      "own those; safe scoped authoring commands under the authoring discipline are allowed. Report evidence " +
      "for each finding and state any unresolved gap explicitly. " +
      "When the remaining gaps are wrong because the code already satisfies them, pass the claim object as the " +
      "`no_change` argument of feature_task_phase_complete, with per-criterion evidence, instead of a repair report. " +
      "Either change files or return a no-change claim, never both. " +
      ImplementationPromptSections.NO_CHANGE_CLAIM_DIRECTIVE + " " +
      "Completed repairs return to a fresh source audit. " +
      "When every in-scope production finding is addressed, report that in ordinary prose. " +
      "Finish every production item in the saved plan before reporting the repair satisfied. " +
      "One guard or one safe edit is not completion when the saved plan still has missing behavior. " +
      "Continue implementing while any in-scope production item remains and authorized source work can proceed. " +
      "Do not end the session after a small edit, a plan reconciliation, or an inventory of remaining work. " +
      "Task size, implementation complexity, and an unproven file-scoped formatter invocation are not reasons " +
      "to stop source repair. If a formatter cannot be scoped safely, record its deferral and continue editing. " +
      "Use compaction to retain the plan and unfinished items when context runs low. " +
      "Do not call feature_task_phase_complete with progress or no_progress for unfinished source work. " +
      "The completion tool rejects those handoffs without saving a settlement; continue implementing " +
      "in this session and attempt. Do not omit or change the verdict to conceal unfinished work. " +
      "A real process interruption is handled by the runtime, not by declaring partial work complete. " +
      "Every unfinished repair implementation accepted for settlement consumes one of the " +
      "selected criterion's three unsuccessful repair attempts, whether or not it changed source files. " +
      "The runtime preserves edits and reports, then blocks after the third unsuccessful attempt. " +
      "Never use feature_task_phase_block or a " +
      "retryable failure to hand off unfinished implementation. " +
      "The runtime preserves applied edits and saved repair reports across continuation sessions. " +
      "Known unfinished production work is not a needs_user_action blocker. Use that disposition only " +
      "when a concrete missing input or external action prevents further repair, and identify the required " +
      "action. Use non_retryable_policy_conflict only for an irreconcilable governing constraint. " +
      "Retryable failures remain subject to the existing retry budgets; never claim completion to avoid them. " +
      "When the latest audit output begins with `Operator instructions:`, the operator rejected an audit-confirmed " +
      "no-change claim; treat those instructions as part of this step's fix input and the governing repair scope."

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective =
        DIRECTIVE +
          (
            inputs.currentAcceptanceCriterion?.let {
              "\n\nRuntime-selected authored criterion and retained budget:\n$it\nRepair only this " +
                "criterion. Include every remaining sibling of its reported defect class."
            } ?: (
              "\n\nThe runtime did not provide a selected authored criterion. " +
                "Do not edit; report the missing runtime context."
            )
          ) + inputs.priorAcceptanceRepairFailures?.let { "\n\n$it" }.orEmpty(),
      authoringDiscipline = projectAuthoringDisciplineDirective(),
      continuation = continuation(inputs),
      valueContent =
        "value accounts for every supplied finding, including separate gaps under the same criterion. " +
          "For each finding, name the criterion, changed production path and source evidence " +
          "that addresses it, give a concrete unresolved reason, or identify it as a test requirement excluded " +
          "from audit. Explain with source evidence when a " +
          "finding was already satisfied or does not apply. A changed filename alone is not " +
          "evidence. Do not claim a criterion is addressed while one of its reported production gaps remains. Audit " +
          "independently decides whether every production requirement is satisfied. Do not claim test execution. " +
          "Record compact authoring command and deferral evidence in value.",
    )

  private fun continuation(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String {
    val continuation =
      inputs.implementationContinuation?.takeIf { it.phaseId == inputs.briefing.phaseId }
        ?: return ""
    val reports =
      continuation.priorValueSegments.withIndex().joinToString("\n\n") { (index, value) ->
        "Prior repair report ${index + 1}:\n$value"
      }
    return """
      ## Resume the saved audit repair
      Prior repair sessions changed the current checkout but left unfinished work. Reconcile their reports
      with the latest audit and current source. Preserve completed edits and continue the saved plan for
      the runtime-selected criterion. Prior completion claims and test-only classifications remain claims
      to verify. Continue every remaining production item in the saved plan under the current reservation.
      Do not stop after another partial edit while authorized source work can proceed. The completion
      tool rejects progress and no_progress handoffs without saving a settlement. Continue implementing
      in the same attempt; do not conceal unfinished work by omitting or changing the verdict.
      Every unfinished repair accepted for settlement consumes one unsuccessful attempt, even when it
      changed source files. The runtime preserves
      edits and reports; the next attempt receives a new reservation and repair plan. Do not replace the
      plan with another partial guard.

      $reports

      Latest saved repair instruction: ${continuation.latestPrompt ?: "none"}
      Latest failure disposition: ${continuation.failureDisposition ?: "none"}
      """.trimIndent()
  }
}
