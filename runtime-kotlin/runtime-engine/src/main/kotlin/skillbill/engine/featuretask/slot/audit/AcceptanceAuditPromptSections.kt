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
    "On the first audit, verify the production behavior required by every acceptance criterion against the " +
      "current implementation. Exclude all test requirements from audit, even when the plan or a criterion " +
      "explicitly requires tests. Missing tests, coverage, assertions, test quality, fixtures, and test results " +
      "never keep an acceptance criterion open. For a mixed criterion, evaluate only its production behavior. " +
      "Omit test-only criteria from the remaining list without inventing a production requirement for them. " +
      "Preserve the original criterion identifiers and spec text; this exclusion governs audit admission. " +
      "Treat upstream receipts as claims and inspect current production code. Audit is read-only: do not edit " +
      "files or repair gaps. Do not spawn subagents or invoke repair skills. Report remaining acceptance " +
      "criteria with criterion identifiers, concrete missing production behavior, and relevant production " +
      "paths. The runtime passes those findings to audit_plan_fix using the configured reasoning " +
      "model. Report status completed when inspection finishes, including when production criteria remain " +
      "open. The runtime owns repair retries and progress limits. Do not block because finding IDs or counts " +
      "repeat or grow, or because a previous report claimed another repair was prohibited. Report the current " +
      "production gaps and let the runtime apply its durable repair policy. " +
      "A completed inspection with open criteria routes to audit_plan_fix. Downstream review requires " +
      "a report that no production criteria remain. " +
      "After repairs, inspect only the unresolved criteria in the last accepted audit report. Previously " +
      "satisfied criteria stay closed and must never be rechecked or reopened. Apply the same test exclusion. " +
      "When all required production behavior is " +
      "implemented, including when only test requirements remain, report that no production criteria remain " +
      "with only the line \"${AcceptanceAuditRemainingCriteriaParser.COMPLETION_LINE}\" " +
      "Block with a concrete failure_disposition when the criterion list is missing or unreadable or an " +
      "external dependency prevents inspection. " + AUDIT_READONLY_EVIDENCE_SENTENCE

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = DIRECTIVE + auditScope(inputs),
      ceremonyLine =
        "Apply ${ceremonyScalingOf(inputs.briefing).auditCeremony.promptLabel}. " +
          "Inspect the unresolved criteria without " +
          "editing files. Exclude test requirements and report production gaps for the implementation repair step. " +
          "An enforcement guard or architecture check whose implementation a criterion requires counts as " +
          "production behavior even under a test source set; its example and regression cases stay excluded.",
      valueContent =
        "Report the remaining acceptance criteria in prose. When all production requirements are met, " +
          "the whole value is the single line \"${AcceptanceAuditRemainingCriteriaParser.COMPLETION_LINE}\" " +
          "with no rationale and no criterion list. Exclude test-only criteria and test-related parts of " +
          "mixed criteria. Otherwise list only the open criteria: start each on its own line with its briefing " +
          "criterion ID, then give the missing production behavior and relevant production paths. Never name " +
          "a satisfied criterion. " +
          "Open criteria route to audit_plan_fix. Do not repair gaps in audit. Only a report that no " +
          "criteria remain allows downstream review. Every audit " +
          "checks only the unresolved criterion list against the current tree after the first pass. " +
          "Original spec labels are accepted aliases. For capability " +
          "gaps, identify the actual consumer, helper or cast path, and reachable forbidden operation. A cast " +
          "inside an authorized review consumer alone does not prove a non-review access path. " +
          "Report the current findings even when their criterion IDs, count or descriptions repeat. " +
          "The runtime decides whether another repair is allowed. Reserve blocked status for a missing or " +
          "unreadable criterion list or an external dependency that prevents inspection.",
    )

  private fun auditScope(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String {
    val prior = inputs.priorAcceptanceAudit ?: return ""
    val catalog =
      AcceptanceAuditCatalog.create(inputs.briefing.acceptanceCriteria) as? AcceptanceAuditCatalog.Known
        ?: return ""
    return when (val remaining = AcceptanceAuditRemainingCriteriaParser.parse(prior, catalog)) {
      is AcceptanceAuditRemainingCriteria.Known ->
        " This round may inspect only these unresolved criterion IDs: " +
          remaining.identities.sorted().joinToString(", ") +
          ". All other criteria are already satisfied and stay closed. Last accepted findings:\n" + prior
      AcceptanceAuditRemainingCriteria.Complete ->
        " All criteria were already satisfied. Carry completion forward without reopening any criterion."
      is AcceptanceAuditRemainingCriteria.Unusable -> ""
    }
  }
}
