package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.model.audit.AcceptanceAuditInteractionTraceScope
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionInteractionTraceRound
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.PHASE_COMPLETE_SETTLEMENT_TOOL
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf

internal object AcceptanceAuditPromptSections {
  const val AUDIT_READONLY_EVIDENCE_SENTENCE: String =
    "Inspect production code without editing it. Run no compile, build, test, format, lint, or full-check " +
      "command: build owns compilation and build proof, validate owns tests and failures, and audit reports " +
      "gaps for audit_plan_fix."

  const val DEFECT_CLASS_FANOUT_SENTENCE: String =
    "Treat each named production gap as one instance of a remaining defect class. Name the forbidden " +
      "pattern and report every remaining sibling production path in this criterion's family: the same " +
      "store, the same list, board, status, or history seam, and callers of the function you would change. " +
      "Do not leave a cousin path for the next audit round. Do not open a different criterion to chase " +
      "unrelated work. The latest reported gap is context, not a scope limit."

  const val ACCEPTED_RECHECK_SENTENCE: String =
    "Criteria accepted by an earlier audit are re-checked only when the runtime lists them in the " +
      "interaction-trace or re-check scope."

  private const val TRACE_INTRO: String =
    "This is the runtime-selected interaction trace of criteria that are already accepted. " +
      "$ACCEPTED_RECHECK_SENTENCE "

  private const val SCENARIO_DIRECTIVE: String =
    "Trace production code from each pending scenario's entry point, through " +
      "its preconditions and steps, to its outcome, and cite a path:line or path:start-end for every step " +
      "of the trace. Consider every listed same-file pair, even when no scenario names it, and check in the " +
      "shared production files that the later criterion did not undo the earlier one. "

  private const val RECHECK_DIRECTIVE: String =
    "Re-check every listed accepted criterion against the current tree: read the production code its closure " +
      "evidence cites together with the files the repair touched, answer holds with fresh path:line evidence " +
      "when its required behavior still holds, or regressed when a change broke it. A regressed answer " +
      "reopens that criterion for repair. "

  private const val TRACE_COMMON: String =
    "Exclude all test requirements. Stay read-only: do not edit files, spawn subagents, or repair gaps. " +
      "$AUDIT_READONLY_EVIDENCE_SENTENCE "

  private const val SCENARIO_RULES: String =
    "A broken line must name an owner from that scenario's criteria, or " +
      "one of the two members of a pair. Use unknown when source cannot establish the outcome; unknown " +
      "reopens nothing. "

  private const val TRACE_VALUE_CONTENT: String =
    "Report the trace in ordinary prose and end with exactly one Interaction line per pending ref, in the " +
      "grammar given at the end of the scope. There is no Open criteria line and no criterion list. Do not " +
      "repair anything. Malformed output is re-asked once and then recorded as unknown."

  private const val COMBINED_VALUE_CONTENT: String =
    "Report the trace and the re-check in ordinary prose and end with exactly one Interaction line per " +
      "pending ref and exactly one Recheck line per listed criterion, in the grammar given at the end of the " +
      "scope. There is no Open criteria line and no criterion list. Do not repair anything. Malformed output " +
      "is re-asked once, then pending scenarios are recorded as unknown and every re-check as holding."

  private const val RECHECK_ONLY_VALUE_CONTENT: String =
    "Report the re-check in ordinary prose and end with exactly one Recheck line per listed criterion, in the " +
      "grammar given at the end of the scope. There is no Open criteria line and no criterion list. Do not " +
      "repair anything. Malformed output is re-asked once and then every re-check is recorded as holding."

  private fun traceDirective(trace: AcceptanceAuditInteractionTraceScope): String {
    val scenarios = trace.pendingRefs.isNotEmpty()
    val rechecks = trace.recheckRefs.isNotEmpty()
    val regressed = if (rechecks) " or any re-check regressed" else ""
    val lines = listOfNotNull("Interaction".takeIf { scenarios }, "Recheck".takeIf { rechecks }).joinToString(" and ")
    return TRACE_INTRO +
      (if (scenarios) SCENARIO_DIRECTIVE else "") +
      (if (rechecks) RECHECK_DIRECTIVE else "") +
      TRACE_COMMON +
      (if (scenarios) SCENARIO_RULES else "") +
      "Complete through $PHASE_COMPLETE_SETTLEMENT_TOOL with status completed and verdict " +
      "\"advance\" when any pending ref is broken$regressed, otherwise \"satisfied\". The runtime reads the " +
      "$lines lines, not the verdict, and no Open criteria line is required. Block only when an " +
      "external dependency prevents inspection."
  }

  private fun traceValueContent(trace: AcceptanceAuditInteractionTraceScope): String =
    when {
      trace.pendingRefs.isEmpty() -> RECHECK_ONLY_VALUE_CONTENT
      trace.recheckRefs.isEmpty() -> TRACE_VALUE_CONTENT
      else -> COMBINED_VALUE_CONTENT
    }

  const val DIRECTIVE: String =
    "On the initial full audit, verify the production behavior required by every acceptance criterion " +
      "against the current implementation, and end your prose with exactly one line " +
      "`Open criteria: AC-002, AC-005` that names every criterion with a production source gap, or " +
      "`Open criteria: none`. The runtime accepts every criterion that line does not name and repairs the " +
      "named ones one at a time; a test-only criterion is never open. On a focused audit, inspect only the " +
      "authored criterion selected by the runtime. " +
      "Exclude all test requirements from audit, even when the plan or a criterion " +
      "explicitly requires tests. Missing tests, coverage, assertions, test quality, fixtures, and test results " +
      "never keep an acceptance criterion open. For a mixed criterion, evaluate only its production behavior. " +
      "Omit test-only criteria from the remaining list without inventing a production requirement for them. " +
      "Preserve the original criterion identifiers and spec text; this exclusion governs audit admission. " +
      "Treat upstream receipts as claims and inspect current production code. Audit is read-only: do not edit " +
      "files or repair gaps. Do not spawn subagents or invoke repair skills. " +
      "Report remaining acceptance criteria with criterion identifiers, concrete missing production behavior " +
      "and relevant production " +
      "paths. The runtime passes those findings to audit_plan_fix using the configured reasoning " +
      "model. Report status completed when inspection finishes, including when production criteria remain " +
      "open. The runtime owns repair retries and progress limits. Do not block because finding IDs or counts " +
      "repeat or grow, or because a previous report claimed another repair was prohibited. Report the current " +
      "production gaps and let the runtime apply its durable repair policy. " +
      "A completed inspection with open criteria routes to audit_plan_fix. Downstream review requires " +
      "a report that no production criteria remain. " +
      "When the runtime supplies a current criterion, inspect that authored criterion and report its source gaps " +
      "in prose. A focused audit checks every independent production requirement in that criterion; treat earlier " +
      "audit findings as context, not as a complete list of its gaps. $DEFECT_CLASS_FANOUT_SENTENCE " +
      "If the source conclusion is ambiguous, keep " +
      "the criterion open and request read-only clarification. $ACCEPTED_RECHECK_SENTENCE " +
      "Settle with an explicit verdict: when you complete through $PHASE_COMPLETE_SETTLEMENT_TOOL, pass " +
      "verdict \"satisfied\" when the inspected production scope is met and verdict \"advance\" when it has " +
      "source gaps. For a runtime-supplied read-only repair-report admission, follow its narrower verdict " +
      "instructions: progress records one unsuccessful attempt for unfinished implementation, satisfied " +
      "admits a finished repair for independent source verification, and advance records a concrete external " +
      "obstacle. A completion without a verdict is read as a clarification request and repeats this audit. " +
      "When all required production behavior is implemented, say so in ordinary prose, including when only " +
      "test requirements remain. Block with a concrete failure_disposition only when an " +
      "external dependency prevents inspection. " + AUDIT_READONLY_EVIDENCE_SENTENCE

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections {
    val trace = inputs.interactionTraceScope
    return PhaseStepPromptSections(
      taskDirective =
        when {
          trace != null -> traceDirective(trace) + traceScope(trace)
          inputs.noChangeClaim == null -> DIRECTIVE + auditScope(inputs)
          else -> DIRECTIVE + noChangeScope(inputs)
        },
      ceremonyLine =
        "Apply ${ceremonyScalingOf(inputs.briefing).auditCeremony.promptLabel}. " +
          "Inspect the runtime-selected production scope without " +
          "editing files. Exclude test requirements and report production gaps for the implementation repair step. " +
          "An enforcement guard or architecture check whose implementation a criterion requires counts as " +
          "production behavior even under a test source set; its example and regression cases stay excluded.",
      valueContent = if (trace != null) traceValueContent(trace) else ORDINARY_VALUE_CONTENT,
    )
  }

  private const val ORDINARY_VALUE_CONTENT: String =
    "Report source gaps and conclusions in ordinary prose. Say when the current source meets the " +
      "production requirements. Exclude test-only criteria and test-related parts of mixed criteria, and " +
      "state those later test or build obligations separately when relevant. Refer to criteria using their " +
      "briefing labels when useful; apart from the initial full audit's Open criteria line there is no " +
      "fixed format. " +
      "Source gaps route to audit_plan_fix. Do not repair gaps in audit. Every criterion must be accepted " +
      "before downstream review. Focused audits inspect the runtime-selected criterion. " +
      "List every remaining sibling of that criterion's defect class, not only the last named gap. " +
      "For capability " +
      "gaps, identify the actual consumer, helper or cast path, and reachable forbidden operation. A cast " +
      "inside an authorized review consumer alone does not prove a non-review access path. " +
      "Report the current findings even when their criterion IDs, count or descriptions repeat. " +
      "The runtime decides whether another repair is allowed. Reserve blocked status for an external " +
      "dependency that prevents inspection."

  private fun traceScope(trace: AcceptanceAuditInteractionTraceScope): String {
    val pending =
      trace.pendingScenarios.joinToString("\n") { scenario ->
        "- ${scenario.scenarioRef} (criteria ${scenario.criterionRefs.joinToString(", ")}; subtasks " +
          "${scenario.subtaskRefs.joinToString(", ")}): entry point: ${scenario.entryPoint}; preconditions: " +
          "${scenario.preconditions}; steps: ${scenario.steps}; outcome: ${scenario.outcome}"
      }
    val pairs =
      trace.sameFilePairs.joinToString("\n") { pair ->
        val status =
          if (pair.pairRef in trace.pendingRefs) "pending, owners ${pair.firstRef} or ${pair.secondRef}" else "context"
        "- ${pair.pairRef} ($status): shared production files ${pair.sharedPaths.joinToString(", ")}"
      }.ifEmpty { "none" }
    val accepted =
      trace.acceptedCriteria.joinToString("\n") { "- ${it.criterionRef}: ${it.closureEvidenceRef}" }.ifEmpty { "none" }
    val scenarios = trace.pendingRefs.isNotEmpty()
    val rechecks = trace.recheckRefs.isNotEmpty()
    val scenarioScope =
      if (scenarios) {
        "\nPending refs (exactly one Interaction line each): ${trace.pendingRefs.joinToString(", ")}\n" +
          "Pending scenarios:\n${pending.ifEmpty { "none" }}\n" +
          "Same-file pairs (a line is recorded only for a pending ref):\n$pairs\n" +
          "Accepted criteria and their closure evidence:\n$accepted\n"
      } else {
        ""
      }
    return scenarioScope + recheckScope(trace) +
      AcceptanceCriterionInteractionTraceRound.grammar(scenarios, rechecks)
  }

  private fun recheckScope(trace: AcceptanceAuditInteractionTraceScope): String {
    if (trace.recheckRefs.isEmpty()) return ""
    val evidence = trace.acceptedCriteria.associate { it.criterionRef to it.closureEvidenceRef }
    val listed =
      trace.recheckRefs.joinToString("\n") { "- $it: closure evidence ${evidence[it] ?: "none"}" }
    return "\nRe-check refs (exactly one Recheck line each) with their closure evidence:\n$listed\n"
  }

  private fun noChangeScope(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String {
    val claim = inputs.noChangeClaim ?: return ""
    return " The implementation step claims that no change is needed (produced_outputs.no_change). Judge every " +
      "criterion in the claim against the current tree. Read each cited path:line and the boundary trace without " +
      "editing anything. Set verdict to no_change_confirmed when the evidence supports every criterion's verdict. " +
      "Otherwise set verdict to no_change_rejected and give the specific per-criterion reasons in the value. " +
      "End the initial full audit with the Open criteria line required above, including every criterion with " +
      "a production source gap. Later focused audits assess only the runtime-selected criterion, even when " +
      "this claim covers other criteria. Claim:\n" + claim
  }

  private fun auditScope(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String {
    val current = inputs.currentAcceptanceCriterion ?: return ""
    return " Runtime-selected audit context: $current. Use this context for a focused audit."
  }
}
