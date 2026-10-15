package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf

internal object AcceptanceAuditPromptSections {
  const val AUDIT_READONLY_EVIDENCE_SENTENCE: String =
    "Inspect production code without editing it. Run no compile, build, test, format, lint, or full-check " +
      "command: build owns compilation and build proof, validate owns tests and failures, and audit reports " +
      "gaps for audit_plan_fix."

  const val DIRECTIVE: String =
    "On an initial or final full audit, verify the production behavior required by every acceptance criterion " +
      "against the current implementation. On a focused audit, inspect only the authored criterion selected by " +
      "the runtime. " +
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
      "audit findings as context, not as a complete list of its gaps. If the source conclusion is ambiguous, keep " +
      "the criterion open and request read-only clarification. A final full audit inspects " +
      "every authored production requirement in the current source, including " +
      "criteria previously accepted. Apply the same test exclusion. " +
      "Use the existing satisfied verdict when the inspected production scope is met, and " +
      "advance when it has source gaps. " +
      "When all required production behavior is implemented, say so in ordinary prose, including when only " +
      "test requirements remain. Block with a concrete failure_disposition only when an " +
      "external dependency prevents inspection. " + AUDIT_READONLY_EVIDENCE_SENTENCE

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = DIRECTIVE + if (inputs.noChangeClaim == null) auditScope(inputs) else noChangeScope(inputs),
      ceremonyLine =
        "Apply ${ceremonyScalingOf(inputs.briefing).auditCeremony.promptLabel}. " +
          "Inspect the runtime-selected production scope without " +
          "editing files. Exclude test requirements and report production gaps for the implementation repair step. " +
          "An enforcement guard or architecture check whose implementation a criterion requires counts as " +
          "production behavior even under a test source set; its example and regression cases stay excluded.",
      valueContent =
        "Report source gaps and conclusions in ordinary prose. Say when the current source meets the " +
          "production requirements. Exclude test-only criteria and test-related parts of mixed criteria, and " +
          "state those later test or build obligations separately when relevant. Refer to criteria using their " +
          "briefing labels when useful, without requiring a fixed list or format. " +
          "Source gaps route to audit_plan_fix. Do not repair gaps in audit. Final source acceptance is " +
          "required before downstream review. Focused audits inspect the runtime-selected criterion. " +
          "For capability " +
          "gaps, identify the actual consumer, helper or cast path, and reachable forbidden operation. A cast " +
          "inside an authorized review consumer alone does not prove a non-review access path. " +
          "Report the current findings even when their criterion IDs, count or descriptions repeat. " +
          "The runtime decides whether another repair is allowed. Reserve blocked status for an external " +
          "dependency that prevents inspection.",
    )

  private fun noChangeScope(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String {
    val claim = inputs.noChangeClaim ?: return ""
    return " The implementation step claims that no change is needed (produced_outputs.no_change). Judge every " +
      "criterion in the claim against the current tree. Read each cited path:line and the boundary trace without " +
      "editing anything. Set verdict to no_change_confirmed when the evidence supports every criterion's verdict. " +
      "Otherwise set verdict to no_change_rejected and give the specific per-criterion reasons in the value. " +
      "Do not return the remaining-criteria list for this round. Claim:\n" + claim
  }

  private fun auditScope(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String {
    val current = inputs.currentAcceptanceCriterion ?: return ""
    return " Runtime-selected audit context: $current. Use this context for a focused audit; final full audits " +
      "must inspect the full authored production scope."
  }
}
