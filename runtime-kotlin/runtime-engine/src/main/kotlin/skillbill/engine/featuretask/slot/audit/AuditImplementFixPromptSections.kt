package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.projectAuthoringDisciplineDirective

internal object AuditImplementFixPromptSections {
  const val COMPLETION_MARKER: String = "audit_repair_complete: true"

  fun endsWithCompletionMarker(value: String): Boolean =
    value
      .lineSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .toList()
      .dropLastWhile { it == "```" || it == "~~~" }
      .lastOrNull() == COMPLETION_MARKER

  const val DIRECTIVE: String =
    "Execute the persisted audit_plan_fix repair plan in its declared order. The supplied upstream output " +
      "contains that plan and the latest audit findings. Reconcile each planned item against the current " +
      "tree before editing, preserve work already completed, and implement its proposed production changes. " +
      "Report closure evidence for every plan item and explain any source-backed adjustment. Do not " +
      "replace the repair plan with the original feature plan or silently omit a planned gap. " +
      "Repair every finding about production behavior reported by the latest audit in the supplied upstream output. " +
      "Exclude test requirements, even when a persisted audit finding or the plan explicitly requests tests. " +
      "Do not add or repair tests to close an audit criterion. For mixed findings, repair only production " +
      "behavior; record test-only findings as excluded from audit and leave test work to its owning phases. " +
      "An enforcement guard or architecture check whose implementation an acceptance criterion requires is " +
      "in scope even when it lives under a test source set: repair the guard implementation the audit names, " +
      "but leave its example and regression test cases to their owning phases. An earlier audit or repair " +
      "calling a criterion test-only does not exclude a guard implementation that the criterion requires. " +
      "A criterion may contain several independent production gaps: account for each before editing and address " +
      "all of them in this repair run. Use the plan and current repository to implement the missing behavior " +
      "in production. Trace each reported production path through its callers, transaction boundaries, " +
      "and failure handling. A check in an earlier transaction does not protect a later mutation. Reconcile " +
      "already-applied edits and preserve completed work and governed contracts. Before reporting completion, " +
      "trace each supplied finding from its actual consumer through every reachable helper to the named " +
      "operation, and finish any remaining in-scope repair. Renaming a getter, introducing a forwarding " +
      "interface, or moving mutable authority behind a recoverable cast does not close an access finding. " +
      "Do not defer " +
      "known missing behavior to the next audit or validation. Do not spawn subagents or perform another " +
      "full audit. Do not compile, build, run tests, or run a full repository check, because build and validate " +
      "own those; safe scoped authoring commands under the authoring discipline are allowed. Report evidence " +
      "for each finding and state any unresolved gap explicitly. " +
      "The runtime returns to a fresh audit after this step. " +
      "When every in-scope production finding is addressed, end value with the exact marker " +
      "`audit_repair_complete: true` alone on its final content line. " +
      "Never emit it while any production gap remains; " +
      "keep repairing in this repair run instead of returning a partial report. " +
      "The runtime continues incomplete output and retryable failures within this repair step before " +
      "returning to audit. It preserves applied edits and saved repair reports across continuation sessions. " +
      "Known unfinished production work is not a needs_user_action blocker. Use that disposition only " +
      "when a concrete missing input or external action prevents further repair, and identify the required " +
      "action. Use non_retryable_policy_conflict only for an irreconcilable governing constraint. " +
      "Retryable failures remain subject to the existing retry budgets; never claim completion to avoid them."

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = DIRECTIVE,
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
          "Record compact authoring command and deferral evidence in value before the completion marker, which " +
          "stays the last content line.",
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
      with the latest audit and current source. Preserve completed edits and finish every remaining finding
      in this repair run. Prior completion claims and test-only classifications remain claims to verify.

      $reports

      Latest saved repair instruction: ${continuation.latestPrompt ?: "none"}
      Latest failure disposition: ${continuation.failureDisposition ?: "none"}
      """.trimIndent()
  }
}
