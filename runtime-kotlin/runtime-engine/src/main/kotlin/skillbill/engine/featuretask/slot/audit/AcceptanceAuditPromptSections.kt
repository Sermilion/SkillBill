package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf

internal object AcceptanceAuditPromptSections {
  const val AUDIT_READONLY_EVIDENCE_SENTENCE: String =
    "Inspect production code without editing it. Run no compile, build, test, format, lint, or full-check " +
      "command: build owns compilation and build proof, validate owns tests and failures, and audit reports " +
      "gaps for audit_implement_fix."

  const val DIRECTIVE: String =
    "Verify the production behavior required by every acceptance criterion in the briefing against the " +
      "current implementation. Exclude all test requirements from audit, even when the plan or a criterion " +
      "explicitly requires tests. Missing tests, coverage, assertions, test quality, fixtures, and test results " +
      "never keep an acceptance criterion open. For a mixed criterion, evaluate only its production behavior. " +
      "Omit test-only criteria from the remaining list without inventing a production requirement for them. " +
      "Preserve the original criterion identifiers and spec text; this exclusion governs audit admission. " +
      "Treat upstream receipts as claims and inspect current production code. Audit is read-only: do not edit " +
      "files or repair gaps. Do not spawn subagents or invoke repair skills. Report remaining acceptance " +
      "criteria with criterion identifiers, concrete missing production behavior, and relevant production " +
      "paths. The runtime passes those findings to audit_implement_fix using the configured implementation " +
      "model. After repairs, re-check the entire in-scope criterion list from the beginning, including previously " +
      "satisfied criteria, applying the same test exclusion. Say plainly that no production criteria remain " +
      "when all required production behavior is implemented, including when only test requirements remain. " +
      "Block with a concrete failure_disposition when the criterion list is missing or unreadable or an " +
      "external dependency prevents inspection. " + AUDIT_READONLY_EVIDENCE_SENTENCE

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = DIRECTIVE,
      ceremonyLine =
        "Apply ${ceremonyScalingOf(inputs.briefing).auditCeremony.promptLabel}. Inspect every criterion without " +
          "editing files. Exclude test requirements and report production gaps for the implementation repair step. " +
          "An enforcement guard or architecture check whose implementation a criterion requires counts as " +
          "production behavior even under a test source set; its example and regression cases stay excluded.",
      valueContent =
        "Report the remaining acceptance criteria in prose. When all production requirements are met, " +
          "say plainly that no production criteria remain and give the satisfied rationale. Exclude test-only " +
          "criteria and test-related parts of mixed criteria. " +
          "Otherwise name each remaining criterion by its briefing criterion ID, the missing production " +
          "behavior, and relevant production paths. " +
          "Open criteria route to audit_implement_fix. Do not repair gaps in audit. Only a report that no " +
          "criteria remain allows downstream review. Every audit " +
          "checks the complete planned criterion list against the current tree. " +
          "Original spec labels are accepted aliases. Name a satisfied criterion only to say it is satisfied. " +
          "For capability " +
          "gaps, identify the actual consumer, helper or cast path, and reachable forbidden operation. A cast " +
          "inside an authorized review consumer alone does not prove a non-review access path. " +
          "Another automatic repair requires fewer open criterion IDs than before repair; equal or larger " +
          "counts block for operator intervention, even when the IDs or descriptions changed.",
    )
}
